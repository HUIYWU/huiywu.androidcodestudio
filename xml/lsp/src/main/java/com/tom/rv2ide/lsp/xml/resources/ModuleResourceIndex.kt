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

    val extractionsByFile = cache.extractionsByFile.toMutableMap()
    val activeResourceFiles =
        FileManager.getActiveDocumentFiles().filter { file ->
          file.toString().endsWith(XML_SUFFIX) && directories.any { file.normalize().startsWith(it) }
        }
    for (file in activeResourceFiles) {
      val text = runCatching { FileManager.getDocumentContents(file) }.getOrNull()
          ?: return ResourceSnapshot.Unavailable
      extractionsByFile[file.normalize()] = ResourceDefinitionExtractor.extract(file, text)
    }

    // The request's text is newer than FileManager until its document event has been published.
    if (directories.any { currentFile.normalize().startsWith(it) } &&
        currentFile.toString().endsWith(XML_SUFFIX)) {
      extractionsByFile[currentFile.normalize()] =
          ResourceDefinitionExtractor.extract(currentFile, currentText)
    }
    return snapshotDefinitions(extractionsByFile)
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

          val extractionsByFile = mutableMapOf<Path, ResourceDefinitionExtractor.Extraction>()
          signatures.forEach { (file, signature) ->
            val cached =
                previous
                    ?.takeIf { it.directories == directories }
                    ?.takeIf { it.signatures[file] == signature }
                    ?.extractionsByFile
                    ?.get(file)
            extractionsByFile[file] =
                cached ?: ResourceDefinitionExtractor.extract(file, FileManager.getDocumentContents(file))
          }
          ModuleCache(directories, signatures, extractionsByFile, refreshedAtMillis)
        }
        .getOrNull()
  }

  private data class ModuleCache(
      val directories: Set<Path>,
      val signatures: Map<Path, FileSignature>,
      val extractionsByFile: Map<Path, ResourceDefinitionExtractor.Extraction>,
      val refreshedAtMillis: Long,
  )

  private data class FileSignature(val modifiedMillis: Long, val size: Long)

  private const val DISK_REFRESH_INTERVAL_MILLIS = 1_000L
  private const val XML_SUFFIX = ".xml"
}
