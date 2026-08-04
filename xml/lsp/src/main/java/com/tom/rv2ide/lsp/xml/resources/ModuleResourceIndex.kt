/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package com.tom.rv2ide.lsp.xml.resources

import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Cached editable-resource snapshot for the active Android module.
 *
 * Resource-table snapshots remain responsible for completion and dependency/framework resolution.
 * This index deliberately contains only workspace resource XML definitions that later operations may
 * edit. It mirrors [ModuleResourceIdIndex]'s defensive disk-cache and active-document policy.
 */
internal object ModuleResourceIndex {
  private val caches = ConcurrentHashMap<String, ModuleCache>()

  /** Releases all module snapshots when the owning XML language server is shut down. */
  fun clear() {
    caches.clear()
  }

  /** Exact lightweight-entry counts for cache observation; this intentionally does not estimate bytes. */
  fun stats(): CacheStats {
    var files = 0
    var definitions = 0
    var occurrences = 0
    caches.values.forEach { cache ->
      files += cache.entriesByFile.size
      cache.entriesByFile.values.forEach { entry ->
        if (entry is ResourceFileEntry.Available) {
          definitions += entry.definitions.size
          occurrences += entry.occurrences.size
        }
      }
    }
    return CacheStats(caches.size, files, definitions, occurrences)
  }

  fun snapshot(currentFile: Path, currentText: String): ResourceSnapshot {
    val module =
        Lookup.getDefault().lookup(ModuleProject.COMPLETION_MODULE_KEY) as? AndroidModule
            ?: return ResourceSnapshot.Unavailable
    val directories = module.getResourceDirectories().map { it.toPath().normalize() }.toSet()
    if (directories.isEmpty()) return ResourceSnapshot.Unavailable

    val now = System.currentTimeMillis()
    val previous = caches[module.path]
    val cache =
        if (previous != null &&
            previous.directories == directories &&
            now - previous.refreshedAtMillis < DISK_REFRESH_INTERVAL_MILLIS) {
          previous
        } else {
          refreshDisk(directories, previous, now) ?: return ResourceSnapshot.Unavailable
        }
    caches[module.path] = cache

    val entriesByFile = cache.entriesByFile.toMutableMap()
    val activeResourceFiles =
        FileManager.getActiveDocumentFiles().filter { file ->
          file.toString().endsWith(XML_SUFFIX) && directories.any { file.normalize().startsWith(it) }
        }
    for (file in activeResourceFiles) {
      val text = runCatching { FileManager.getDocumentContents(file) }.getOrNull()
          ?: return ResourceSnapshot.Unavailable
      entriesByFile[file.normalize()] = ResourceFileEntry.create(file, text)
    }

    // The request's text is newer than FileManager until its document event has been published.
    if (directories.any { currentFile.normalize().startsWith(it) } &&
        currentFile.toString().endsWith(XML_SUFFIX)) {
      entriesByFile[currentFile.normalize()] = ResourceFileEntry.create(currentFile, currentText)
    }
    return snapshotEntries(entriesByFile)
  }

  private fun refreshDisk(
      directories: Set<Path>,
      previous: ModuleCache?,
      refreshedAtMillis: Long,
  ): ModuleCache? {
    return runCatching {
          val signatures = mutableMapOf<Path, FileSignature>()
          directories.forEach { directory ->
            if (!Files.exists(directory)) return@forEach
            Files.walk(directory).use { paths ->
              paths
                  .filter { file -> Files.isRegularFile(file) && file.toString().endsWith(XML_SUFFIX) }
                  .forEach { file ->
                    val normalized = file.normalize()
                    signatures[normalized] =
                        FileSignature(Files.getLastModifiedTime(file).toMillis(), Files.size(file))
                  }
            }
          }

          val entriesByFile = mutableMapOf<Path, ResourceFileEntry>()
          signatures.forEach { (file, signature) ->
            val cached =
                previous
                    ?.takeIf { it.directories == directories }
                    ?.takeIf { it.signatures[file] == signature }
                    ?.entriesByFile
                    ?.get(file)
            entriesByFile[file] =
                cached
                    ?: FileManager.getDocumentContents(file).let { text ->
                      ResourceFileEntry.create(file, text)
                    }
          }
          ModuleCache(directories, signatures, entriesByFile, refreshedAtMillis)
        }
        .getOrNull()
  }

  private data class ModuleCache(
      val directories: Set<Path>,
      val signatures: Map<Path, FileSignature>,
      val entriesByFile: Map<Path, ResourceFileEntry>,
      val refreshedAtMillis: Long,
  )

  internal data class CacheStats(
      val moduleCount: Int,
      val fileCount: Int,
      val definitionCount: Int,
      val occurrenceCount: Int,
  )

  private data class FileSignature(val modifiedMillis: Long, val size: Long)

  private const val DISK_REFRESH_INTERVAL_MILLIS = 1_000L
  private const val XML_SUFFIX = ".xml"
}
