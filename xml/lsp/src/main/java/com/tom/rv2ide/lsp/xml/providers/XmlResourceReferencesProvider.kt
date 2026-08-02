/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.providers

import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceResult
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

/** Provides conservative workspace-only XML resource references. */
internal class XmlResourceReferencesProvider {

  fun findReferences(params: ReferenceParams): ReferenceResult {
    params.cancelChecker.abortIfCancelled()
    val currentText = runCatching { FileManager.getDocumentContents(params.file) }.getOrNull()
        ?: return ReferenceResult(emptyList())
    val cursor = params.position.requireIndex()
    val snapshot = ModuleResourceIndex.snapshot(params.file, currentText) as? ResourceSnapshot.Available
        ?: return ReferenceResult(emptyList())
    val target = targetFor(params.file, currentText, cursor, snapshot) ?: return ReferenceResult(emptyList())

    return ReferenceResult(
        findInSnapshot(
            target = target,
            snapshot = snapshot,
            includeDeclaration = params.includeDeclaration,
            readText = { file -> if (file.normalize() == params.file.normalize()) currentText else FileManager.getDocumentContents(file) },
            checkCancelled = params.cancelChecker::abortIfCancelled,
        ) ?: emptyList()
    )
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
      readText: (Path) -> String,
      checkCancelled: () -> Unit = {},
  ): List<Location>? {
    val occurrencesByFile = linkedMapOf<Path, List<ResourceReferenceOccurrence>>()
    for (file in snapshot.files.sortedBy(Path::toString)) {
      checkCancelled()
      val scan = runCatching { ResourceReferenceScanner.scan(readText(file)) }.getOrNull()
          as? ResourceReferenceScanner.ScanResult.Available
          ?: return null
      occurrencesByFile[file] = scan.occurrences
    }
    checkCancelled()
    return ResourceReferencesQuery.find(
        target = target,
        definitions = snapshot.definitions,
        occurrencesByFile = occurrencesByFile,
        includeDeclaration = includeDeclaration,
    )
  }
}