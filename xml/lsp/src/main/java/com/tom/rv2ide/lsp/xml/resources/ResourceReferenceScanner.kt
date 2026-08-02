/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.resources

import com.android.aaptcompiler.AaptResourceType
import com.tom.rv2ide.lsp.xml.diagnostics.XmlResourceReference
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import org.eclipse.lemminx.dom.DOMAttr
import org.eclipse.lemminx.dom.DOMNode
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.dom.DOMText
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager

/** Finds only complete, editable XML resource references in one document. */
internal object ResourceReferenceScanner {

  fun scan(text: String): ScanResult {
    val document =
        runCatching {
              DOMParser.getInstance()
                  .parse(text, ANDROID_NAMESPACE_URI, URIResolverExtensionManager())
            }
            .getOrNull()
            ?: return ScanResult.Unavailable
    if (hasSyntaxRecovery(document)) return ScanResult.Unavailable

    val occurrences = mutableListOf<ResourceReferenceOccurrence>()
    collect(document, text, occurrences)
    return ScanResult.Available(occurrences.sortedBy { it.startOffset })
  }

  fun targetAt(text: String, offset: Int): ResourceReferenceOccurrence? {
    if (offset !in 0..text.length) return null
    val result = scan(text) as? ScanResult.Available ?: return null
    return result.occurrences.firstOrNull { occurrence -> offset in occurrence.startOffset until occurrence.endOffset }
  }

  private fun collect(
      node: DOMNode,
      text: String,
      occurrences: MutableList<ResourceReferenceOccurrence>,
  ) {
    if (node is org.eclipse.lemminx.dom.DOMElement) {
      node.attributeNodes.orEmpty().forEach { attribute ->
        attributeOccurrence(attribute, text)?.let(occurrences::add)
      }
    }
    if (node is DOMText) {
      textOccurrence(node, text)?.let(occurrences::add)
    }
    node.children.forEach { child -> collect(child, text, occurrences) }
  }

  private fun attributeOccurrence(
      attribute: DOMAttr,
      text: String,
  ): ResourceReferenceOccurrence? {
    if (attribute.namespaceURI == TOOLS_NAMESPACE_URI) return null
    val value = attribute.value ?: return null
    if (value.startsWith(DATA_BINDING_PREFIX) || value.startsWith(TWO_WAY_DATA_BINDING_PREFIX)) return null
    val reference = parseReference(value) ?: return null
    val range = attributeValueOffsets(attribute, text) ?: return null
    return occurrence(reference, text, range.first, range.second)
  }

  private fun textOccurrence(textNode: DOMText, text: String): ResourceReferenceOccurrence? {
    if (!textNode.isText) return null
    val raw = textNode.data
    val value = raw.trim()
    if (value.isEmpty() || value.startsWith(DATA_BINDING_PREFIX) || value.startsWith(TWO_WAY_DATA_BINDING_PREFIX)) {
      return null
    }
    val reference = parseReference(value) ?: return null
    val leading = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    val start = textNode.startContent + leading
    return occurrence(reference, text, start, start + value.length)
  }

  private fun parseReference(value: String): ParsedReference? {
    if (XmlResourceReference.isSpecialValue(value)) return null
    if (value.startsWith(CREATING_ID_PREFIX)) {
      val normalized = "@${value.removePrefix(CREATING_ID_PREFIX)}"
      val reference = XmlResourceReference.parse(normalized) ?: return null
      if (reference.packageName == null && !reference.isThemeAttribute && reference.type == AaptResourceType.ID) {
        return ParsedReference(reference, true)
      }
      return null
    }
    return XmlResourceReference.parse(value)?.let { ParsedReference(it, false) }
  }

  private fun occurrence(
      parsed: ParsedReference,
      text: String,
      start: Int,
      end: Int,
  ): ResourceReferenceOccurrence? {
    if (start !in 0..text.length || end !in start..text.length) return null
    return ResourceReferenceOccurrence(
        reference = parsed.reference,
        range = Range(offsetToPosition(text, start), offsetToPosition(text, end)),
        startOffset = start,
        endOffset = end,
        isCreatingId = parsed.isCreatingId,
    )
  }

  private fun attributeValueOffsets(attribute: DOMAttr, text: String): Pair<Int, Int>? {
    val node = attribute.nodeAttrValue ?: return null
    var start = node.start.coerceIn(0, text.length)
    var end = node.end.coerceIn(start, text.length)
    if (end - start >= 2 && text[start] in QUOTES && text[end - 1] == text[start]) {
      start++
      end--
    }
    return start to end
  }

  private fun hasSyntaxRecovery(node: DOMNode): Boolean {
    if (node is org.eclipse.lemminx.dom.DOMElement &&
        (node.isOrphanEndTag ||
            (node.hasStartTag() && !node.isSelfClosed && (!node.isStartTagClosed || !node.isClosed)))) {
      return true
    }
    return node.children.any(::hasSyntaxRecovery)
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

  internal sealed interface ScanResult {
    data class Available(val occurrences: List<ResourceReferenceOccurrence>) : ScanResult

    data object Unavailable : ScanResult
  }

  private data class ParsedReference(val reference: XmlResourceReference, val isCreatingId: Boolean)

  private const val ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android"
  private const val TOOLS_NAMESPACE_URI = "http://schemas.android.com/tools"
  private const val CREATING_ID_PREFIX = "@+"
  private const val DATA_BINDING_PREFIX = "@{"
  private const val TWO_WAY_DATA_BINDING_PREFIX = "@={"
  private const val QUOTES = "\"'"
}

/** A complete resource reference occurrence with a range excluding XML quotes and whitespace. */
internal data class ResourceReferenceOccurrence(
    val reference: XmlResourceReference,
    val range: Range,
    internal val startOffset: Int,
    internal val endOffset: Int,
    val isCreatingId: Boolean,
)