/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.resources

import java.nio.file.Path

/**
 * Immutable resource facts derived together from one XML text snapshot.
 *
 * The entry deliberately keeps no source text or DOM nodes. Definitions and occurrences are accepted
 * only as one unit so a cached module snapshot cannot combine facts produced from different document
 * revisions.
 */
internal sealed interface ResourceFileEntry {
  data class Available(
      val definitions: List<ResourceDefinition>,
      val occurrences: List<ResourceReferenceOccurrence>,
  ) : ResourceFileEntry

  data object Unavailable : ResourceFileEntry

  companion object {
    fun create(file: Path, text: String): ResourceFileEntry {
      val extraction = ResourceDefinitionExtractor.extract(file, text)
      val scan = ResourceReferenceScanner.scan(text)
      if (extraction !is ResourceDefinitionExtractor.Extraction.Available ||
          scan !is ResourceReferenceScanner.ScanResult.Available) {
        return Unavailable
      }
      return Available(extraction.definitions, scan.occurrences)
    }
  }
}
