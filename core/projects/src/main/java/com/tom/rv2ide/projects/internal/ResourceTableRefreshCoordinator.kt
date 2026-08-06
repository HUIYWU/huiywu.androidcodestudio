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
    val key = module.path
    val state = pending.computeIfAbsent(key) { PendingRefresh() }
    synchronized(state) {
      val sequence = state.sequence.incrementAndGet()
      state.future?.cancel(false)
      state.future =
          executor.schedule(
              { refresh(module, key, state, sequence) },
              if (immediate) 0L else debounceMillis,
              TimeUnit.MILLISECONDS,
          )
    }
  }

  private fun refresh(
      module: AndroidModule,
      key: String,
      state: PendingRefresh,
      sequence: Long,
  ) {
    if (state.sequence.get() != sequence) return
    val inputs = snapshotInputs(module)
    module.refreshResourceTable(inputs) { state.sequence.get() != sequence }
    if (state.sequence.get() == sequence) {
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

  private companion object {
    const val DEBOUNCE_MILLIS = 400L
    const val XML_SUFFIX = ".xml"
  }
}
