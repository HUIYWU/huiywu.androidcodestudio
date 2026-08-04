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

  fun scan(text: String): ScanResult = scanInternal(text, null).scan

  internal fun scanMeasured(text: String): MeasuredScan {
    val timing = OccurrenceTiming()
    val scan = scanInternal(text, timing).scan
    return MeasuredScan(scan, timing)
  }

  private fun scanInternal(text: String, timing: OccurrenceTiming?): ScanWithTiming {
    val parseStartedAtNanos = timing?.let { System.nanoTime() }
    val document =
        runCatching {
              DOMParser.getInstance()
                  .parse(text, ANDROID_NAMESPACE_URI, URIResolverExtensionManager())
            }
            .getOrNull()
    if (timing != null) timing.domParseNanos += System.nanoTime() - checkNotNull(parseStartedAtNanos)
    document ?: return ScanWithTiming(ScanResult.Unavailable)

    val recoveryStartedAtNanos = timing?.let { System.nanoTime() }
    val hasRecovery = hasSyntaxRecovery(document)
    if (timing != null) timing.syntaxRecoveryNanos += System.nanoTime() - checkNotNull(recoveryStartedAtNanos)
    if (hasRecovery) return ScanWithTiming(ScanResult.Unavailable)

    val positionIndexStartedAtNanos = timing?.let { System.nanoTime() }
    val positions = PositionIndex(text)
    if (timing != null) timing.positionIndexNanos += System.nanoTime() - checkNotNull(positionIndexStartedAtNanos)
    val occurrences = mutableListOf<ResourceReferenceOccurrence>()
    val traversalStartedAtNanos = timing?.let { System.nanoTime() }
    collect(document, text, positions, occurrences, timing)
    if (timing != null) timing.traversalNanos += System.nanoTime() - checkNotNull(traversalStartedAtNanos)
    val sortStartedAtNanos = timing?.let { System.nanoTime() }
    val sortedOccurrences = occurrences.sortedBy { it.startOffset }
    if (timing != null) timing.sortNanos += System.nanoTime() - checkNotNull(sortStartedAtNanos)
    return ScanWithTiming(ScanResult.Available(sortedOccurrences))
  }

  fun targetAt(text: String, offset: Int): ResourceReferenceOccurrence? {
    if (offset !in 0..text.length) return null
    val result = scan(text) as? ScanResult.Available ?: return null
    return result.occurrences.firstOrNull { occurrence -> offset in occurrence.startOffset until occurrence.endOffset }
  }

  private fun collect(
      node: DOMNode,
      text: String,
      positions: PositionIndex,
      occurrences: MutableList<ResourceReferenceOccurrence>,
      timing: OccurrenceTiming?,
  ) {
    if (node is org.eclipse.lemminx.dom.DOMElement) {
      node.attributeNodes.orEmpty().forEach { attribute ->
        attributeOccurrence(attribute, text, positions, timing)?.let { occurrence ->
          occurrences += occurrence
          if (timing != null) {
            timing.attributeOccurrences++
            if (occurrence.isCreatingId) timing.creatingIdOccurrences++
          }
        }
      }
    }
    if (node is DOMText) {
      textOccurrence(node, text, positions, timing)?.let { occurrence ->
        occurrences += occurrence
        if (timing != null) {
          timing.textOccurrences++
          if (occurrence.isCreatingId) timing.creatingIdOccurrences++
        }
      }
    }
    node.children.forEach { child -> collect(child, text, positions, occurrences, timing) }
  }

  private fun attributeOccurrence(
      attribute: DOMAttr,
      text: String,
      positions: PositionIndex,
      timing: OccurrenceTiming?,
  ): ResourceReferenceOccurrence? {
    if (attribute.namespaceURI == TOOLS_NAMESPACE_URI) return null
    val value = attribute.value ?: return null
    if (value.startsWith(DATA_BINDING_PREFIX) || value.startsWith(TWO_WAY_DATA_BINDING_PREFIX)) return null
    val reference = parseMeasuredReference(value, timing) ?: return null
    val range = attributeValueOffsets(attribute, text) ?: return null
    return buildMeasuredOccurrence(reference, positions, range.first, range.second, timing)
  }

  private fun textOccurrence(
      textNode: DOMText,
      text: String,
      positions: PositionIndex,
      timing: OccurrenceTiming?,
  ): ResourceReferenceOccurrence? {
    if (!textNode.isText) return null
    val raw = textNode.data
    val value = raw.trim()
    if (value.isEmpty() || value.startsWith(DATA_BINDING_PREFIX) || value.startsWith(TWO_WAY_DATA_BINDING_PREFIX)) {
      return null
    }
    val reference = parseMeasuredReference(value, timing) ?: return null
    val leading = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    val start = textNode.startContent + leading
    return buildMeasuredOccurrence(reference, positions, start, start + value.length, timing)
  }

  private fun parseMeasuredReference(
      value: String,
      timing: OccurrenceTiming?,
  ): ParsedReference? {
    val startedAtNanos = timing?.let { System.nanoTime() }
    val reference = parseReference(value)
    if (timing != null) timing.referenceParseNanos += System.nanoTime() - checkNotNull(startedAtNanos)
    return reference
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

  private fun buildMeasuredOccurrence(
      parsed: ParsedReference,
      positions: PositionIndex,
      start: Int,
      end: Int,
      timing: OccurrenceTiming?,
  ): ResourceReferenceOccurrence? {
    val startedAtNanos = timing?.let { System.nanoTime() }
    val occurrence = occurrence(parsed, positions, start, end)
    if (timing != null) timing.occurrenceBuildNanos += System.nanoTime() - checkNotNull(startedAtNanos)
    return occurrence
  }

  private fun occurrence(
      parsed: ParsedReference,
      positions: PositionIndex,
      start: Int,
      end: Int,
  ): ResourceReferenceOccurrence? {
    if (!positions.contains(start) || !positions.contains(end) || end < start) return null
    return ResourceReferenceOccurrence(
        reference = parsed.reference,
        range = Range(positions.positionAt(start), positions.positionAt(end)),
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

  private class PositionIndex(text: String) {
    private val textLength = text.length
    private val lineStarts = buildLineStarts(text)

    fun contains(offset: Int): Boolean = offset in 0..textLength

    fun positionAt(offset: Int): Position {
      var low = 0
      var high = lineStarts.lastIndex
      while (low <= high) {
        val middle = (low + high) ushr 1
        if (lineStarts[middle] <= offset) {
          low = middle + 1
        } else {
          high = middle - 1
        }
      }
      return Position(high, offset - lineStarts[high])
    }

    private fun buildLineStarts(text: String): IntArray {
      val starts = ArrayList<Int>()
      starts += 0
      text.forEachIndexed { index, character ->
        if (character == '\n') starts += index + 1
      }
      return starts.toIntArray()
    }
  }

  internal data class MeasuredScan(
      val scan: ScanResult,
      val timing: OccurrenceTiming,
  )

  internal data class OccurrenceTiming(
      var domParseNanos: Long = 0L,
      var syntaxRecoveryNanos: Long = 0L,
      var traversalNanos: Long = 0L,
      var positionIndexNanos: Long = 0L,
      var referenceParseNanos: Long = 0L,
      var occurrenceBuildNanos: Long = 0L,
      var sortNanos: Long = 0L,
      var attributeOccurrences: Int = 0,
      var textOccurrences: Int = 0,
      var creatingIdOccurrences: Int = 0,
  )

  private data class ScanWithTiming(val scan: ScanResult)

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