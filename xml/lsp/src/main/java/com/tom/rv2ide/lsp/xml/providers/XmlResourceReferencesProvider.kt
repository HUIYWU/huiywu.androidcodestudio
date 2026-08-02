/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.providers

import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.xml.resources.ModuleResourceIndex
import com.tom.rv2ide.lsp.xml.resources.ResourceReferenceOccurrence
import com.tom.rv2ide.lsp.xml.resources.ResourceReferenceScanner
import com.tom.rv2ide.lsp.xml.resources.ResourceReferencesQuery
import com.tom.rv2ide.lsp.xml.resources.ResourceSnapshot
import com.tom.rv2ide.models.Location
import com.tom.rv2ide.projects.FileManager
import java.nio.file.Path

/** Provides conservative workspace-only XML resource references. */
internal class XmlResourceReferencesProvider {

  fun findReferences(params: ReferenceParams): ReferenceResult {
    params.cancelChecker.abortIfCancelled()
    val currentText = runCatching { FileManager.getDocumentContents(params.file) }.getOrNull()
        ?: return ReferenceResult(emptyList())
    val target = ResourceReferenceScanner.targetAt(currentText, params.position.requireIndex())
        ?: return ReferenceResult(emptyList())
    val snapshot = ModuleResourceIndex.snapshot(params.file, currentText) as? ResourceSnapshot.Available
        ?: return ReferenceResult(emptyList())

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