/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.projects.internal

import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.xml.resources.ResourceTableFileInput
import com.tom.rv2ide.xml.resources.ResourceTableInputSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * Serializes workspace resource-table refreshes and coalesces editor changes per Android module.
 *
 * The coordinator owns scheduling only. Resource parsing and atomic table publication remain in
 * [AndroidModule] and [com.tom.rv2ide.xml.resources.ResourceTableRegistry].
 */
internal class ResourceTableRefreshCoordinator(
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
) : AutoCloseable {

  private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "resource-table-refresh").apply { isDaemon = true }
  }
  private val pending = ConcurrentHashMap<String, PendingRefresh>()

  fun schedule(module: AndroidModule, immediate: Boolean = false) {
    scheduleInternal(
        key = module.path,
        immediate = immediate,
        snapshot = { snapshotInputs(module) },
        publish = { inputs, isObsolete -> module.refreshResourceTable(inputs, isObsolete) },
    )
  }

  internal fun scheduleForTest(
      key: String,
      immediate: Boolean = false,
      snapshot: () -> ResourceTableInputSnapshot,
      publish: (ResourceTableInputSnapshot, () -> Boolean) -> Unit,
  ) {
    scheduleInternal(key, immediate, snapshot, publish)
  }

  private fun scheduleInternal(
      key: String,
      immediate: Boolean,
      snapshot: () -> ResourceTableInputSnapshot,
      publish: (ResourceTableInputSnapshot, () -> Boolean) -> Unit,
  ) {
    val state = pending.computeIfAbsent(key) { PendingRefresh() }
    synchronized(state) {
      val sequence = state.sequence.incrementAndGet()
      val scheduledAtNanos = System.nanoTime()
      state.future?.cancel(false)
      state.future =
          executor.schedule(
              { refresh(key, state, sequence, scheduledAtNanos, immediate, snapshot, publish) },
              if (immediate) 0L else debounceMillis,
              TimeUnit.MILLISECONDS,
          )
    }
  }

  private fun refresh(
      key: String,
      state: PendingRefresh,
      sequence: Long,
      scheduledAtNanos: Long,
      immediate: Boolean,
      snapshot: () -> ResourceTableInputSnapshot,
      publish: (ResourceTableInputSnapshot, () -> Boolean) -> Unit,
  ) {
    if (state.sequence.get() != sequence) return
    val startedAtNanos = System.nanoTime()
    val inputs = snapshot()
    val snapshotNanos = System.nanoTime() - startedAtNanos
    publish(inputs) { state.sequence.get() != sequence }
    val obsolete = state.sequence.get() != sequence
    if (log.isDebugEnabled) {
      log.debug(
          "Resource table refresh coordinator: module={} sequence={} immediate={} queueMs={} snapshotMs={} memoryInputs={} outcome={}",
          key,
          sequence,
          immediate,
          nanosToMillis(startedAtNanos - scheduledAtNanos),
          nanosToMillis(snapshotNanos),
          inputs.size,
          if (obsolete) "obsolete" else "completed",
      )
    }
    if (!obsolete) {
      pending.remove(key, state)
    }
  }

  private fun snapshotInputs(module: AndroidModule): ResourceTableInputSnapshot {
    val valuesDirectories =
        module.mainSourceSet?.sourceProvider?.resDirectories
            ?.map { directory -> directory.toPath().normalize().resolve("values") }
            ?.toSet()
            .orEmpty()
    if (valuesDirectories.isEmpty()) return ResourceTableInputSnapshot.EMPTY

    val activeFiles =
        FileManager.getActiveDocumentFiles().filter { file ->
          file.toString().endsWith(XML_SUFFIX) &&
              valuesDirectories.any { values -> file.normalize().startsWith(values) }
        }
    val snapshots = FileManager.getActiveDocumentSnapshots(activeFiles)
    if (snapshots.isEmpty()) return ResourceTableInputSnapshot.EMPTY

    return ResourceTableInputSnapshot.of(
        snapshots.mapValues { (_, snapshot) ->
          ResourceTableFileInput(snapshot.content, snapshot.revision)
        }
    )
  }

  override fun close() {
    pending.values.forEach { it.future?.cancel(false) }
    pending.clear()
    executor.shutdownNow()
  }

  private class PendingRefresh {
    val sequence = AtomicLong()

    @Volatile var future: ScheduledFuture<*>? = null
  }

  private fun nanosToMillis(nanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(nanos)

  private companion object {
    val log = LoggerFactory.getLogger(ResourceTableRefreshCoordinator::class.java)
    const val DEBOUNCE_MILLIS = 400L
    const val XML_SUFFIX = ".xml"
  }
}
