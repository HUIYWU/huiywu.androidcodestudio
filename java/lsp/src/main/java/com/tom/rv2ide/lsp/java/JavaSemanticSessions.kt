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
import com.tom.rv2ide.projects.ModuleProject
import java.util.concurrent.ConcurrentHashMap
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

  val environmentGeneration: Long
    get() = generation.get()

  fun compiler(): JavaCompilerService = JavaCompilerProvider.get(module)

  fun invalidateEnvironment(): Long {
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
