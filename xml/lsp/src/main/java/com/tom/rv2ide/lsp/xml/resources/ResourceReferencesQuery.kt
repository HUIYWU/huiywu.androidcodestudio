/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.resources

import com.tom.rv2ide.models.Location
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import java.nio.file.Path

/**
 * Pure workspace-only resource references query.
 *
 * The caller supplies a module definition snapshot and reliable per-document scanner results. This
 * class deliberately has no disk, FileManager, Lookup or LSP-server dependency.
 */
internal object ResourceReferencesQuery {

  fun find(
      target: ResourceReferenceOccurrence,
      definitions: List<ResourceDefinition>,
      occurrencesByFile: Map<Path, List<ResourceReferenceOccurrence>>,
      includeDeclaration: Boolean,
  ): List<Location> {
    // Local theme references (`?attr/name`) identify the same workspace ATTR resource as
    // `@attr/name`. Package-qualified references remain outside the workspace-only index.
    if (target.reference.packageName != null) return emptyList()

    val targetType = target.reference.type
    val targetName = target.reference.entry
    val matchingDefinitions = definitions.filter { definition ->
      definition.type == targetType && definition.name == targetName
    }
    if (matchingDefinitions.isEmpty()) return emptyList()

    val locations = mutableListOf<Location>()
    if (includeDeclaration) {
      matchingDefinitions.forEach { definition -> locations += declarationLocation(definition) }
    }
    occurrencesByFile.toSortedMap(compareBy(Path::toString)).forEach { (file, occurrences) ->
      occurrences
          .asSequence()
          .filter { occurrence ->
            occurrence.reference.packageName == null &&
                occurrence.reference.type == targetType &&
                occurrence.reference.entry == targetName
          }
          // @+id is represented by the exact ID_DECLARATION range above, not by its whole reference.
          .filterNot { it.isCreatingId }
          .forEach { occurrence -> locations += Location(file, occurrence.range) }
    }
    return locations.distinct()
  }

  private fun declarationLocation(definition: ResourceDefinition): Location {
    return Location(definition.sourceFile, definition.nameRange ?: Range(Position(0, 0), Position(0, 0)))
  }

  // Target identity remains local to find(), keeping this query stateless and safe for concurrent use.
}