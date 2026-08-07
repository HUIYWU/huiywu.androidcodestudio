/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.java

import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.ModuleProject
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight semantic ownership boundary for one Java module.
 *
 * The session deliberately does not retain source text, javac trees, or a second compiler. The
 * existing [JavaCompilerProvider] remains the owner of the module's reusable compiler, whose
 * internal [com.tom.rv2ide.lsp.java.compiler.SynchronizedTask] serializes javac access. Keeping
 * this object small makes it safe to retain while a module is active on Android devices.
 *
 * [environmentGeneration] changes whenever classpath/Kotlin ABI/module state invalidates the
 * semantic environment. It is not yet part of a cache key in this phase; it establishes the
 * single source of truth required before request coalescing and semantic-result caching are added.
 */
internal class JavaSemanticSession internal constructor(
    val module: ModuleProject,
    private val nextEnvironmentGeneration: () -> Long,
    initialEnvironmentGeneration: Long,
) {
  private val generation = AtomicLong(initialEnvironmentGeneration)
  private val interactiveRequestCount = AtomicInteger(0)

  val environmentGeneration: Long
    get() = generation.get()

  fun compiler(): JavaCompilerService = JavaCompilerProvider.get(module)

  /**
   * Marks a user-visible semantic request as active.
   *
   * Diagnostics consult this counter immediately before entering javac. They yield instead of
   * queueing behind completion/signature work, which prevents background analysis from extending
   * keystroke latency without adding a second compiler or scheduler thread.
   */
  fun beginInteractiveRequest(): AutoCloseable {
    interactiveRequestCount.incrementAndGet()
    return AutoCloseable { interactiveRequestCount.decrementAndGet() }
  }

  val hasInteractiveRequest: Boolean
    get() = interactiveRequestCount.get() > 0

  /**
   * Immutable identity for a shareable completion computation.
   *
   * The key intentionally includes both version and revision. Version identifies the ordinary
   * editor edit sequence, while revision distinguishes lifecycles such as close/reopen where a
   * document version can legitimately restart at zero. It is declaration-only for now: no worker
   * is shared until UI waiter cancellation is separated from worker cancellation.
   */
  data class CompletionRequestKey(
      val file: Path,
      val documentVersion: Int,
      val documentRevision: Long,
      val environmentGeneration: Long,
      val cursorIndex: Long,
      val prefix: String,
  ) {
    init {
      require(documentVersion >= 0) { "A completion key requires a known document version" }
      require(documentRevision >= 0L) { "A completion key requires a known document revision" }
      require(cursorIndex >= 0L) { "A completion key requires a valid cursor index" }
    }

    companion object {
      fun create(
          file: Path,
          documentVersion: Int,
          documentRevision: Long,
          environmentGeneration: Long,
          cursorIndex: Long,
          prefix: String?,
      ): CompletionRequestKey =
          CompletionRequestKey(
              file = file.normalize(),
              documentVersion = documentVersion,
              documentRevision = documentRevision,
              environmentGeneration = environmentGeneration,
              cursorIndex = cursorIndex,
              prefix = prefix.orEmpty(),
          )
    }
  }

  /**
   * Tracks UI subscriptions independently from the future shared worker cancellation policy.
   *
   * This is intentionally not connected to javac yet. It records the required invariant for the
   * next phase: detaching one UI waiter must never cancel a computation still observed by another.
   */
  internal class CompletionSubscribers {
    private val count = AtomicInteger(0)

    fun attach(): AutoCloseable {
      count.incrementAndGet()
      val detached = AtomicBoolean(false)
      return AutoCloseable {
        if (detached.compareAndSet(false, true)) {
          check(count.decrementAndGet() >= 0) { "Completion subscriber count underflow" }
        }
      }
    }

    val isEmpty: Boolean
      get() = count.get() == 0

    val size: Int
      get() = count.get()
  }

  /**
   * Session-owned in-flight completion state.
   *
   * The [workerCancelChecker] belongs to the computation, never to a UI CompletionThread. A
   * subscriber can detach without stopping the worker while another subscriber remains. This type
   * is intentionally not yet awaited from UI threads; it establishes cleanup and ownership rules
   * before a cancellable, non-blocking waiter bridge is introduced.
   */
  internal class InFlightCompletion internal constructor(
      val key: CompletionRequestKey,
  ) {
    val workerCancelChecker = ICancelChecker.Default()
    val result = CompletableFuture<CompletionResult>()
    private val subscribers = CompletionSubscribers()
    private val terminal = AtomicBoolean(false)

    fun attachSubscriber(): AutoCloseable = subscribers.attach()

    val subscriberCount: Int
      get() = subscribers.size

    val hasSubscribers: Boolean
      get() = !subscribers.isEmpty

    /** Completes once; returns false when cancellation or another completion won the race. */
    fun complete(workerResult: CompletionResult): Boolean {
      if (!terminal.compareAndSet(false, true)) {
        return false
      }
      return result.complete(workerResult)
    }

    /** Completes exceptionally once; returns false when the worker already published a result. */
    fun fail(error: Throwable): Boolean {
      if (!terminal.compareAndSet(false, true)) {
        return false
      }
      return result.completeExceptionally(error)
    }

    fun cancelWorkerIfUnobserved() {
      if (subscribers.isEmpty && !result.isDone) {
        cancelWorker()
      }
    }

    fun cancelWorker() {
      workerCancelChecker.cancel()
      fail(CancellationException("Completion worker cancelled"))
    }
  }

  private val inFlightCompletions = ConcurrentHashMap<CompletionRequestKey, InFlightCompletion>()

  /**
   * Returns the only in-flight state for [key], together with whether this caller created it.
   *
   * A later integration must execute the worker only when [created] is true and remove the exact
   * state in a completion callback. This avoids replacing an active computation for the same key.
   */
  fun acquireInFlightCompletion(key: CompletionRequestKey): Pair<InFlightCompletion, Boolean> {
    val created = InFlightCompletion(key)
    val existing = inFlightCompletions.putIfAbsent(key, created)
    return if (existing == null) created to true else existing to false
  }

  fun removeInFlightCompletion(state: InFlightCompletion) {
    inFlightCompletions.remove(state.key, state)
  }

  /**
   * Cancels only stale states for [file]. EventBus listeners can run asynchronously, so an older
   * event may arrive after a newer request has already been registered. Comparing revisions rather
   * than cancelling every state for the file prevents that late event from killing current work.
   */
  fun cancelInFlightCompletionsOlderThan(file: Path, revision: Long) {
    val normalizedFile = file.normalize()
    inFlightCompletions.values.forEach { state ->
      if (state.key.file == normalizedFile && state.key.documentRevision < revision) {
        if (inFlightCompletions.remove(state.key, state)) {
          state.cancelWorker()
        }
      }
    }
  }

  /** A closed document has no valid completion consumer regardless of its last revision. */
  fun cancelInFlightCompletionsForFile(file: Path) {
    val normalizedFile = file.normalize()
    inFlightCompletions.values.forEach { state ->
      if (state.key.file == normalizedFile && inFlightCompletions.remove(state.key, state)) {
        state.cancelWorker()
      }
    }
  }

  private fun cancelInFlightCompletions() {
    inFlightCompletions.values.forEach { it.cancelWorker() }
    inFlightCompletions.clear()
  }

  fun invalidateEnvironment(): Long {
    cancelInFlightCompletions()
    val nextGeneration = nextEnvironmentGeneration()
    generation.set(nextGeneration)
    return nextGeneration
  }
}

/**
 * Process-local registry of active module semantic sessions.
 *
 * No compiler, document text, or AST is duplicated here. Removing a session also destroys its
 * reusable compiler so module eviction and workspace resets release the heavy javac state.
 */
internal object JavaSemanticSessions {
  private val sessions = ConcurrentHashMap<ModuleProject, JavaSemanticSession>()
  private val environmentGeneration = AtomicLong(0)

  fun forModule(module: ModuleProject): JavaSemanticSession =
      sessions.computeIfAbsent(module) {
        JavaSemanticSession(
            module = it,
            nextEnvironmentGeneration = environmentGeneration::incrementAndGet,
            initialEnvironmentGeneration = environmentGeneration.incrementAndGet(),
        )
      }

  /** Returns an existing session without allocating one solely to cancel stale work. */
  fun existingForModule(module: ModuleProject): JavaSemanticSession? = sessions[module]

  /** Cancels stale work across existing sessions without allocating a compiler session. */
  fun cancelInFlightCompletionsOlderThan(file: Path, revision: Long) {
    sessions.values.forEach { it.cancelInFlightCompletionsOlderThan(file, revision) }
  }

  /** Cancels all completion work for a closed file even when its module can no longer be resolved. */
  fun cancelInFlightCompletionsForFile(file: Path) {
    sessions.values.forEach { it.cancelInFlightCompletionsForFile(file) }
  }

  fun isCurrent(session: JavaSemanticSession, generation: Long): Boolean =
      sessions[session.module] === session && session.environmentGeneration == generation

  fun invalidate(module: ModuleProject): Long = forModule(module).invalidateEnvironment()

  fun destroy(module: ModuleProject) {
    sessions.remove(module)?.invalidateEnvironment()
    JavaCompilerProvider.getInstance().destroy(module)
  }

  fun destroyAll() {
    sessions.values.forEach { it.invalidateEnvironment() }
    sessions.clear()
    JavaCompilerProvider.getInstance().destroy()
  }
}
