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
import org.slf4j.LoggerFactory

/**
 * Cached editable-resource snapshot for the active Android module.
 *
 * Resource-table snapshots remain responsible for completion and dependency/framework resolution.
 * This index deliberately contains only workspace resource XML definitions that later operations may
 * edit. It mirrors [ModuleResourceIdIndex]'s defensive disk-cache and active-document policy.
 */
internal object ModuleResourceIndex {
  private val caches = ConcurrentHashMap<String, ModuleCache>()
  private val log = LoggerFactory.getLogger(ModuleResourceIndex::class.java)

  /** Releases all module snapshots when the owning XML language server is shut down. */
  fun clear() {
    caches.clear()
  }

  /** Exact lightweight-entry counts for cache observation; this intentionally does not estimate bytes. */
  fun stats(): CacheStats = statsFor(caches.values.map { it.entriesByFile })

  internal fun statsFor(entriesByModule: Collection<Map<Path, ResourceFileEntry>>): CacheStats {
    var files = 0
    var definitions = 0
    var occurrences = 0
    entriesByModule.forEach { entriesByFile ->
      files += entriesByFile.size
      entriesByFile.values.forEach { entry ->
        if (entry is ResourceFileEntry.Available) {
          definitions += entry.definitions.size
          occurrences += entry.occurrences.size
        }
      }
    }
    return CacheStats(entriesByModule.size, files, definitions, occurrences)
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
          val measureEntries = log.isDebugEnabled
          val totalStartedAtNanos = if (measureEntries) System.nanoTime() else 0L
          val signatures = mutableMapOf<Path, FileSignature>()
          val walkStartedAtNanos = if (measureEntries) System.nanoTime() else 0L
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
          val walkNanos = if (measureEntries) System.nanoTime() - walkStartedAtNanos else 0L

          var reusedEntries = 0
          var rebuiltEntries = 0
          var readNanos = 0L
          var definitionNanos = 0L
          var occurrenceNanos = 0L
          var valuesFiles = 0
          var fileResourceFiles = 0
          var valuesDefinitions = 0
          var fileResourceDefinitions = 0
          var valuesDefinitionNanos = 0L
          var valuesDomParseNanos = 0L
          var valuesSyntaxRecoveryNanos = 0L
          var valuesElementTraversalNanos = 0L
          var valuesCreatingIdNanos = 0L
          var largestValuesDefinitionCount = 0
          var largestValuesDefinitionNanos = 0L
          var valuesFilesOver100Definitions = 0
          var valuesFilesOver1000Definitions = 0
          var fileResourceDefinitionNanos = 0L
          val entriesByFile = mutableMapOf<Path, ResourceFileEntry>()
          signatures.forEach { (file, signature) ->
            val cached =
                previous
                    ?.takeIf { it.directories == directories }
                    ?.takeIf { it.signatures[file] == signature }
                    ?.entriesByFile
                    ?.get(file)
            if (cached != null) {
              reusedEntries++
              entriesByFile[file] = cached
            } else {
              rebuiltEntries++
              val readStartedAtNanos = if (measureEntries) System.nanoTime() else 0L
              val text = FileManager.getDocumentContents(file)
              if (measureEntries) readNanos += System.nanoTime() - readStartedAtNanos
              if (measureEntries) {
                val measured = ResourceFileEntry.createMeasured(file, text)
                definitionNanos += measured.definitionNanos
                occurrenceNanos += measured.occurrenceNanos
                val definitionCount =
                    (measured.entry as? ResourceFileEntry.Available)?.definitions?.size ?: 0
                when (ResourceDefinitionExtractor.categoryOf(file)) {
                  ResourceDefinitionExtractor.Category.VALUES -> {
                    valuesFiles++
                    valuesDefinitions += definitionCount
                    valuesDefinitionNanos += measured.definitionNanos
                    val valuesTiming = checkNotNull(measured.valuesTiming)
                    valuesDomParseNanos += valuesTiming.domParseNanos
                    valuesSyntaxRecoveryNanos += valuesTiming.syntaxRecoveryNanos
                    valuesElementTraversalNanos += valuesTiming.elementTraversalNanos
                    valuesCreatingIdNanos += valuesTiming.creatingIdNanos
                    largestValuesDefinitionCount = maxOf(largestValuesDefinitionCount, definitionCount)
                    largestValuesDefinitionNanos =
                        maxOf(largestValuesDefinitionNanos, measured.definitionNanos)
                    if (definitionCount > VALUES_DEFINITIONS_LARGE_FILE_THRESHOLD) {
                      valuesFilesOver100Definitions++
                    }
                    if (definitionCount > VALUES_DEFINITIONS_VERY_LARGE_FILE_THRESHOLD) {
                      valuesFilesOver1000Definitions++
                    }
                  }
                  ResourceDefinitionExtractor.Category.FILE -> {
                    fileResourceFiles++
                    fileResourceDefinitions += definitionCount
                    fileResourceDefinitionNanos += measured.definitionNanos
                  }
                  ResourceDefinitionExtractor.Category.NONE -> Unit
                }
                entriesByFile[file] = measured.entry
              } else {
                entriesByFile[file] = ResourceFileEntry.create(file, text)
              }
            }
          }
          if (measureEntries) {
            log.debug(
                "XML resource snapshot refresh: files={} reusedEntries={} rebuiltEntries={} walkMs={} readMs={} definitionMs={} occurrenceMs={} valuesFiles={} valuesDefinitions={} valuesDefinitionMs={} valuesDomParseMs={} valuesSyntaxRecoveryMs={} valuesElementTraversalMs={} valuesCreatingIdMs={} largestValuesDefinitionCount={} largestValuesDefinitionMs={} valuesFilesOver100Definitions={} valuesFilesOver1000Definitions={} fileResourceFiles={} fileResourceDefinitions={} fileResourceDefinitionMs={} totalMs={}",
                signatures.size,
                reusedEntries,
                rebuiltEntries,
                nanosToMillis(walkNanos),
                nanosToMillis(readNanos),
                nanosToMillis(definitionNanos),
                nanosToMillis(occurrenceNanos),
                valuesFiles,
                valuesDefinitions,
                nanosToMillis(valuesDefinitionNanos),
                nanosToMillis(valuesDomParseNanos),
                nanosToMillis(valuesSyntaxRecoveryNanos),
                nanosToMillis(valuesElementTraversalNanos),
                nanosToMillis(valuesCreatingIdNanos),
                largestValuesDefinitionCount,
                nanosToMillis(largestValuesDefinitionNanos),
                valuesFilesOver100Definitions,
                valuesFilesOver1000Definitions,
                fileResourceFiles,
                fileResourceDefinitions,
                nanosToMillis(fileResourceDefinitionNanos),
                nanosToMillis(System.nanoTime() - totalStartedAtNanos),
            )
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

  private fun nanosToMillis(nanos: Long): Long = nanos / NANOS_PER_MILLISECOND

  private const val NANOS_PER_MILLISECOND = 1_000_000L
  private const val VALUES_DEFINITIONS_LARGE_FILE_THRESHOLD = 100
  private const val VALUES_DEFINITIONS_VERY_LARGE_FILE_THRESHOLD = 1_000
  private const val DISK_REFRESH_INTERVAL_MILLIS = 1_000L
  private const val XML_SUFFIX = ".xml"
}
