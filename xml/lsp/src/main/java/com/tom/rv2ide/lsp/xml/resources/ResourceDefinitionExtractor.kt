/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package com.tom.rv2ide.lsp.xml.resources

import com.android.aaptcompiler.AaptResourceType
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import java.nio.file.Path
import org.eclipse.lemminx.dom.DOMAttr
import org.eclipse.lemminx.dom.DOMElement
import org.eclipse.lemminx.dom.DOMNode
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager

/**
 * Extracts editable resource definitions from one workspace resource XML document.
 *
 * This is intentionally a pure, document-local component. Cache ownership, disk traversal and
 * active-editor overlays belong to the later module snapshot layer.
 */
internal object ResourceDefinitionExtractor {

  fun extract(file: Path, text: String): Extraction {
    return when (val resourcePath = resourcePath(file)) {
      null -> Extraction.Available(emptyList())
      ResourcePath.Values -> valuesDefinitions(file, text)
      is ResourcePath.File -> fileDefinitions(file, text, resourcePath)
    }
  }

  /** Debug-only extraction details; normal callers use [extract] and do not perform timing. */
  internal fun extractMeasured(file: Path, text: String): MeasuredExtraction {
    return when (val resourcePath = resourcePath(file)) {
      null -> MeasuredExtraction(Extraction.Available(emptyList()), null)
      ResourcePath.Values -> {
        val valuesTiming = ValuesTiming()
        MeasuredExtraction(valuesDefinitions(file, text, valuesTiming), valuesTiming)
      }
      is ResourcePath.File -> MeasuredExtraction(fileDefinitions(file, text, resourcePath), null)
    }
  }

  /** Classifies a path with the same resource-directory rules used by [extract]. */
  internal fun categoryOf(file: Path): Category {
    return when (resourcePath(file)) {
      ResourcePath.Values -> Category.VALUES
      is ResourcePath.File -> Category.FILE
      null -> Category.NONE
    }
  }

  private fun valuesDefinitions(
      file: Path,
      text: String,
      timing: ValuesTiming? = null,
  ): Extraction {
    val parseStartedAtNanos = timing?.let { System.nanoTime() }
    val document =
        runCatching {
              DOMParser.getInstance()
                  .parse(text, ANDROID_NAMESPACE_URI, URIResolverExtensionManager())
            }
            .getOrNull()
    if (timing != null) timing.domParseNanos += System.nanoTime() - checkNotNull(parseStartedAtNanos)
    document ?: return Extraction.Unavailable

    val recoveryStartedAtNanos = timing?.let { System.nanoTime() }
    val hasRecovery = hasSyntaxRecovery(document)
    if (timing != null) timing.syntaxRecoveryNanos += System.nanoTime() - checkNotNull(recoveryStartedAtNanos)
    if (hasRecovery) return Extraction.Unavailable

    val root = document.documentElement ?: return Extraction.Available(emptyList())
    if (root.tagName != VALUES_ROOT_TAG) return Extraction.Available(emptyList())
    val positions = PositionIndex(text)
    val traversalStartedAtNanos = timing?.let { System.nanoTime() }
    val valueDefinitions =
        root.children.filterIsInstance<DOMElement>().mapNotNull { element ->
          val type = valueType(element) ?: return@mapNotNull null
          definitionFromNameAttribute(
              file,
              text,
              type,
              element.getAttributeNode(NAME_ATTRIBUTE),
              if (type == AaptResourceType.ID) {
                ResourceDefinitionKind.ID_DECLARATION
              } else {
                ResourceDefinitionKind.VALUE_ELEMENT
              },
              positions,
          )
        }
    val nestedStyleableAttrDefinitions = styleableAttrDefinitions(file, text, root, positions)
    if (timing != null) timing.elementTraversalNanos += System.nanoTime() - checkNotNull(traversalStartedAtNanos)
    val creatingIdsStartedAtNanos = timing?.let { System.nanoTime() }
    val creatingIds = creatingIdDefinitions(file, text, document, positions)
    if (timing != null) timing.creatingIdNanos += System.nanoTime() - checkNotNull(creatingIdsStartedAtNanos)
    return Extraction.Available(valueDefinitions + nestedStyleableAttrDefinitions + creatingIds)
  }

  private fun fileDefinitions(file: Path, text: String, path: ResourcePath.File): Extraction {
    // File resources remain real resources even if their contents are currently incomplete.
    val fileResource =
        file.fileName
            ?.toString()
            ?.removeSuffix(XML_SUFFIX)
            ?.takeIf { RESOURCE_NAME.matches(it) }
            ?.let { name ->
              ResourceDefinition(
                  type = path.type,
                  name = name,
                  sourceFile = file,
                  nameRange = null,
                  kind = ResourceDefinitionKind.FILE_RESOURCE,
              )
            }
    val document =
        runCatching {
              DOMParser.getInstance()
                  .parse(text, ANDROID_NAMESPACE_URI, URIResolverExtensionManager())
            }
            .getOrNull()
    if (document == null || hasSyntaxRecovery(document)) {
      return Extraction.Available(listOfNotNull(fileResource))
    }
    return Extraction.Available(listOfNotNull(fileResource) + creatingIdDefinitions(file, text, document))
  }

  private fun styleableAttrDefinitions(
      file: Path,
      text: String,
      root: DOMElement,
      positions: PositionIndex,
  ): List<ResourceDefinition> {
    return root.children
        .filterIsInstance<DOMElement>()
        .filter { it.tagName == DECLARE_STYLEABLE_TAG }
        .flatMap { styleable ->
          styleable.children.filterIsInstance<DOMElement>().mapNotNull { attribute ->
            if (attribute.tagName != ATTR_TAG) return@mapNotNull null
            definitionFromNameAttribute(
                file,
                text,
                AaptResourceType.ATTR,
                attribute.getAttributeNode(NAME_ATTRIBUTE),
                ResourceDefinitionKind.VALUE_ELEMENT,
                positions,
            )
          }
        }
  }

  private fun creatingIdDefinitions(
      file: Path,
      text: String,
      document: DOMNode,
      positions: PositionIndex? = null,
  ): List<ResourceDefinition> {
    val definitions = mutableListOf<ResourceDefinition>()

    fun collect(node: DOMNode) {
      if (node is DOMElement) {
        node.attributeNodes.orEmpty().forEach { attribute ->
          val match = CREATING_ID.matchEntire(attribute.value.orEmpty()) ?: return@forEach
          val offsets = attributeValueOffsets(attribute, text) ?: return@forEach
          val nameStart = offsets.first + CREATING_ID_PREFIX.length
          val nameEnd = offsets.second
          definitions +=
              ResourceDefinition(
                  type = AaptResourceType.ID,
                  name = match.groupValues[1],
                  sourceFile = file,
                  nameRange =
                      Range(
                          positions?.positionAt(nameStart) ?: offsetToPosition(text, nameStart),
                          positions?.positionAt(nameEnd) ?: offsetToPosition(text, nameEnd),
                      ),
                  kind = ResourceDefinitionKind.ID_DECLARATION,
              )
        }
      }
      node.children.forEach(::collect)
    }

    collect(document)
    return definitions
  }

  private fun attributeValueOffsets(attribute: DOMAttr, text: String): Pair<Int, Int>? {
    val value = attribute.nodeAttrValue ?: return null
    var start = value.start.coerceIn(0, text.length)
    var end = value.end.coerceIn(start, text.length)
    if (end - start >= 2 && text[start] in QUOTES && text[end - 1] == text[start]) {
      start++
      end--
    }
    return start to end
  }

  private fun valueType(element: DOMElement): AaptResourceType? {
    val tag = element.tagName ?: return null
    if (tag == ITEM_TAG) {
      return AaptResourceType.ID.takeIf { element.getAttribute(TYPE_ATTRIBUTE) == ID_TYPE_NAME }
    }
    if (tag in UNSUPPORTED_VALUE_TAGS) return null
    return AaptResourceType.values().firstOrNull { it != AaptResourceType.UNKNOWN && it.tagName == tag }
  }

  private fun definitionFromNameAttribute(
      file: Path,
      text: String,
      type: AaptResourceType,
      attribute: DOMAttr?,
      kind: ResourceDefinitionKind,
      positions: PositionIndex? = null,
  ): ResourceDefinition? {
    val name = attribute?.value?.takeIf { RESOURCE_NAME.matches(it) } ?: return null
    val range = attributeValueRange(text, attribute, positions) ?: return null
    return ResourceDefinition(type, name, file, range, kind)
  }

  private fun attributeValueRange(
      text: String,
      attribute: DOMAttr,
      positions: PositionIndex? = null,
  ): Range? {
    val value = attribute.nodeAttrValue ?: return null
    var start = value.start.coerceIn(0, text.length)
    var end = value.end.coerceIn(start, text.length)
    if (end - start >= 2 && text[start] in QUOTES && text[end - 1] == text[start]) {
      start++
      end--
    }
    return Range(
        positions?.positionAt(start) ?: offsetToPosition(text, start),
        positions?.positionAt(end) ?: offsetToPosition(text, end),
    )
  }

  private fun resourcePath(file: Path): ResourcePath? {
    val parts = file.normalize().iterator().asSequence().map(Path::toString).toList()
    val resIndex = parts.indexOfLast { it == RES_DIRECTORY }
    if (resIndex < 0 || resIndex + 2 >= parts.size) return null
    val baseDirectory = parts[resIndex + 1].substringBefore('-')
    if (baseDirectory == VALUES_DIRECTORY) return ResourcePath.Values
    val type = AaptResourceType.values().firstOrNull { it.tagName == baseDirectory } ?: return null
    return ResourcePath.File(type)
  }

  private fun hasSyntaxRecovery(node: DOMNode): Boolean {
    if (node is DOMElement &&
        (node.isOrphanEndTag ||
            (node.hasStartTag() && !node.isSelfClosed && (!node.isStartTagClosed || !node.isClosed)))) {
      return true
    }
    return node.children.any(::hasSyntaxRecovery)
  }

  private class PositionIndex(text: String) {
    private val lineStarts = buildLineStarts(text)

    fun positionAt(offset: Int): Position {
      val boundedOffset = offset.coerceIn(0, textLength)
      var low = 0
      var high = lineStarts.lastIndex
      while (low <= high) {
        val middle = (low + high) ushr 1
        if (lineStarts[middle] <= boundedOffset) {
          low = middle + 1
        } else {
          high = middle - 1
        }
      }
      return Position(high, boundedOffset - lineStarts[high])
    }

    private val textLength = text.length

    private fun buildLineStarts(text: String): IntArray {
      val starts = ArrayList<Int>()
      starts += 0
      text.forEachIndexed { index, character ->
        if (character == '\n') starts += index + 1
      }
      return starts.toIntArray()
    }
  }

  private fun offsetToPosition(text: String, offset: Int): Position {
    var line = 0
    var lineStart = 0
    for (index in 0 until offset) {
      if (text[index] == '\n') {
        line++
        lineStart = index + 1
      }
    }
    return Position(line, offset - lineStart)
  }

  internal data class MeasuredExtraction(
      val extraction: Extraction,
      val valuesTiming: ValuesTiming?,
  )

  internal data class ValuesTiming(
      var domParseNanos: Long = 0L,
      var syntaxRecoveryNanos: Long = 0L,
      var elementTraversalNanos: Long = 0L,
      var creatingIdNanos: Long = 0L,
  )

  internal enum class Category {
    VALUES,
    FILE,
    NONE,
  }

  internal sealed interface Extraction {
    data class Available(val definitions: List<ResourceDefinition>) : Extraction

    data object Unavailable : Extraction
  }

  private sealed interface ResourcePath {
    data object Values : ResourcePath

    data class File(val type: AaptResourceType) : ResourcePath
  }

  private const val ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android"
  private const val RES_DIRECTORY = "res"
  private const val VALUES_DIRECTORY = "values"
  private const val VALUES_ROOT_TAG = "resources"
  private const val ITEM_TAG = "item"
  private const val ATTR_TAG = "attr"
  private const val DECLARE_STYLEABLE_TAG = "declare-styleable"
  private const val TYPE_ATTRIBUTE = "type"
  private const val NAME_ATTRIBUTE = "name"
  private const val ID_TYPE_NAME = "id"
  private const val XML_SUFFIX = ".xml"
  private const val QUOTES = "\"'"
  private const val CREATING_ID_PREFIX = "@+id/"
  private val UNSUPPORTED_VALUE_TAGS = setOf("declare-styleable", "public", "overlayable")
  private val RESOURCE_NAME = Regex("[a-z][a-z0-9_]*")
  private val CREATING_ID = Regex("@\\+id/([a-z][a-z0-9_]*)")
}

internal data class ResourceDefinition(
    val type: AaptResourceType,
    val name: String,
    val sourceFile: Path,
    /** Null for file resources because changing their name requires a file rename, not TextEdit. */
    val nameRange: Range?,
    val kind: ResourceDefinitionKind,
)

internal enum class ResourceDefinitionKind {
  VALUE_ELEMENT,
  FILE_RESOURCE,
  ID_DECLARATION,
}

/** Immutable per-module view consumed later by XML references and restricted rename. */
internal sealed interface ResourceSnapshot {
  data class Available(
      val definitions: List<ResourceDefinition>,
      /** All indexed resource XML files, including files that contribute only usages. */
      val files: Set<Path>,
      /** Complete-reference occurrences derived from the same per-file text snapshot as [definitions]. */
      val occurrencesByFile: Map<Path, List<ResourceReferenceOccurrence>>,
  ) : ResourceSnapshot

  data object Unavailable : ResourceSnapshot
}

/**
 * Combines immutable per-file entries after active documents and the current request have replaced
 * their disk counterparts. Keeping this pure makes precedence testable without an Android module.
 */
internal fun snapshotEntries(entriesByFile: Map<Path, ResourceFileEntry>): ResourceSnapshot {
  val definitions = mutableListOf<ResourceDefinition>()
  val occurrencesByFile = linkedMapOf<Path, List<ResourceReferenceOccurrence>>()
  entriesByFile.forEach { (file, entry) ->
    when (entry) {
      is ResourceFileEntry.Available -> {
        definitions += entry.definitions
        occurrencesByFile[file] = entry.occurrences
      }
      ResourceFileEntry.Unavailable -> return ResourceSnapshot.Unavailable
    }
  }
  return ResourceSnapshot.Available(definitions, entriesByFile.keys, occurrencesByFile)
}
