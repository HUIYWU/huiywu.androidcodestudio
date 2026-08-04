/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.providers

import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.ReferenceRole
import com.tom.rv2ide.lsp.xml.resources.ModuleResourceIndex
import com.tom.rv2ide.lsp.xml.diagnostics.XmlResourceReference
import com.tom.rv2ide.lsp.xml.resources.ResourceDefinition
import com.tom.rv2ide.lsp.xml.resources.ResourceDefinitionKind
import com.tom.rv2ide.lsp.xml.resources.ResourceReferenceOccurrence
import com.tom.rv2ide.lsp.xml.resources.ResourceReferenceScanner
import com.tom.rv2ide.lsp.xml.resources.ResourceReferencesQuery
import com.tom.rv2ide.lsp.xml.resources.ResourceSnapshot
import com.tom.rv2ide.models.Location
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.FileManager
import java.nio.file.Path
import org.slf4j.LoggerFactory

/** Provides conservative workspace-only XML resource references. */
internal class XmlResourceReferencesProvider {

  fun findReferences(params: ReferenceParams): ReferenceResult {
    val startedAtNanos = System.nanoTime()
    params.cancelChecker.abortIfCancelled()
    val currentText = runCatching { FileManager.getDocumentContents(params.file) }.getOrNull()
        ?: return ReferenceResult(emptyList())
    val cursor = params.position.requireIndex()
    val snapshotStartedAtNanos = System.nanoTime()
    val snapshot = ModuleResourceIndex.snapshot(params.file, currentText) as? ResourceSnapshot.Available
        ?: return ReferenceResult(emptyList())
    val snapshotMillis = elapsedMillis(snapshotStartedAtNanos)
    val target = targetFor(params.file, currentText, cursor, snapshot) ?: return ReferenceResult(emptyList())

    val scanStartedAtNanos = System.nanoTime()
    val locations =
        findInSnapshot(
            target = target,
            snapshot = snapshot,
            includeDeclaration = params.includeDeclaration,
            checkCancelled = params.cancelChecker::abortIfCancelled,
        )
    val scanMillis = elapsedMillis(scanStartedAtNanos)
    val roles = rolesFor(target, snapshot, locations)
    if (log.isDebugEnabled) {
      val cacheStats = ModuleResourceIndex.stats()
      log.debug(
          "XML references performance: mode=cache snapshotMs={} scanMs={} totalMs={} files={} results={} cachedModules={} cachedFiles={} cachedDefinitions={} cachedOccurrences={}",
          snapshotMillis,
          scanMillis,
          elapsedMillis(startedAtNanos),
          snapshot.files.size,
          locations.size,
          cacheStats.moduleCount,
          cacheStats.fileCount,
          cacheStats.definitionCount,
          cacheStats.occurrenceCount,
      )
    }
    return ReferenceResult(locations, roles)
  }

  internal fun rolesFor(
      target: ResourceReferenceOccurrence,
      snapshot: ResourceSnapshot.Available,
      locations: List<Location>,
  ): List<ReferenceRole> {
    val definitions =
        snapshot.definitions
            .asSequence()
            .filter {
              it.type == target.reference.type && it.name == target.reference.entry
            }
            .map { definition ->
              Location(definition.sourceFile, definition.nameRange ?: Range(Position(0, 0), Position(0, 0)))
            }
            .toSet()
    return locations.map { location ->
      if (location in definitions) ReferenceRole.DEFINITION else ReferenceRole.USAGE
    }
  }

  internal fun targetFor(
      file: Path,
      text: String,
      cursor: Int,
      snapshot: ResourceSnapshot.Available,
  ): ResourceReferenceOccurrence? {
    ResourceReferenceScanner.targetAt(text, cursor)?.let { return it }
    val normalizedFile = file.normalize()
    val definition =
        snapshot.definitions.firstOrNull { candidate ->
          candidate.sourceFile.normalize() == normalizedFile &&
              candidate.nameRange?.let { rangeContainsOffset(text, it, cursor) } == true
        }
        ?: snapshot.definitions.singleOrNull { candidate ->
          candidate.sourceFile.normalize() == normalizedFile && candidate.kind == ResourceDefinitionKind.FILE_RESOURCE
        }
        ?: return null
    return definitionTarget(definition)
  }

  private companion object {
    private const val NANOS_PER_MILLISECOND = 1_000_000L
    private val log = LoggerFactory.getLogger(XmlResourceReferencesProvider::class.java)
  }

  private fun elapsedMillis(startedAtNanos: Long): Long {
    return (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND
  }

  private fun definitionTarget(definition: ResourceDefinition): ResourceReferenceOccurrence {
    val range = definition.nameRange ?: Range(Position(0, 0), Position(0, 0))
    return ResourceReferenceOccurrence(
        reference =
            XmlResourceReference(
                text = "@${definition.type.tagName}/${definition.name}",
                packageName = null,
                type = definition.type,
                entry = definition.name,
                isThemeAttribute = false,
            ),
        range = range,
        startOffset = 0,
        endOffset = 0,
        isCreatingId = false,
    )
  }

  private fun rangeContainsOffset(text: String, range: Range, offset: Int): Boolean {
    val start = offsetAt(text, range.start)
    val end = offsetAt(text, range.end)
    return offset in start until end
  }

  private fun offsetAt(text: String, position: Position): Int {
    var line = 0
    var index = 0
    while (index < text.length && line < position.line) {
      if (text[index++] == '\n') line++
    }
    return (index + position.column).coerceIn(index, text.length)
  }

  internal fun findInSnapshot(
      target: ResourceReferenceOccurrence,
      snapshot: ResourceSnapshot.Available,
      includeDeclaration: Boolean,
      checkCancelled: () -> Unit = {},
  ): List<Location> {
    snapshot.files.sortedBy(Path::toString).forEach { checkCancelled() }
    return ResourceReferencesQuery.find(
        target = target,
        definitions = snapshot.definitions,
        occurrencesByFile = snapshot.occurrencesByFile,
        includeDeclaration = includeDeclaration,
    )
  }
}