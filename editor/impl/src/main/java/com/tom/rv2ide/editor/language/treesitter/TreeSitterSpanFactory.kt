/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.editor.language.treesitter

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.tom.rv2ide.editor.schemes.LanguageScheme
import com.itsaky.androidide.treesitter.TSQuery
import com.itsaky.androidide.treesitter.TSQueryCapture
import com.tom.rv2ide.utils.parseHexColor
import io.github.rosemoe.sora.editor.ts.spans.DefaultSpanFactory
import io.github.rosemoe.sora.editor.ts.spans.TsSpanFactory
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.span.SpanConstColorResolver
import io.github.rosemoe.sora.lang.styling.span.SpanExtAttrs
import io.github.rosemoe.sora.text.ContentReference
import org.slf4j.LoggerFactory

/**
 * [TsSpanFactory] for tree sitter languages.
 *
 * @author Akash Yadav
 */
class TreeSitterSpanFactory(
    private var content: ContentReference?,
    private var query: TSQuery?,
    private var styles: Styles?,
    private var langScheme: LanguageScheme?,
) : DefaultSpanFactory() {

  companion object {

    private val log = LoggerFactory.getLogger(TreeSitterSpanFactory::class.java)

    @JvmStatic
    private val HEX_REGEX = "#\\b([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})\\b".toRegex()
  }

  override fun close() {
    content = null
    query = null
    styles = null
    langScheme = null
  }

  override fun createSpans(capture: TSQueryCapture, column: Int, spanStyle: Long): List<Span> {
    val content = this.content?.reference ?: return super.createSpans(capture, column, spanStyle)
    val query = this.query ?: return super.createSpans(capture, column, spanStyle)
    val langScheme = this.langScheme ?: return super.createSpans(capture, column, spanStyle)

    val captureName = query.getCaptureNameForId(capture.index)
    val styleDef = langScheme.getStyles()[captureName]
    val escapeStyle =
        if (captureName == "string" && langScheme.getFileTypes().any { it == "kt" || it == "kts" }) {
          langScheme.getStyles()["string.escape"]?.makeStyle()
        } else {
          null
        }
    if (styleDef?.maybeHexColor != true && escapeStyle == null) {
      return super.createSpans(capture, column, spanStyle)
    }

    val (start, end) =
        content.indexer.run {
          getCharPosition(capture.node.startByte / 2) to getCharPosition(capture.node.endByte / 2)
        }

    if (start.line != end.line || start.column != column) {
      // HEX colors and Kotlin escaped strings are both limited to a single source line here.
      return super.createSpans(capture, column, spanStyle)
    }

    val text = content.subContent(start.line, start.column, end.line, end.column).toString()
    val escapeRanges = escapeStyle?.let { findKotlinEscapeRanges(text) }.orEmpty()
    val colorRanges =
        if (styleDef?.maybeHexColor == true) {
          HEX_REGEX.findAll(text).mapNotNull { result ->
            try {
              result.range.first until (result.range.last + 1) to
                  parseHexColor(result.groupValues[1]).toInt()
            } catch (error: Exception) {
              log.error("An error occurred parsing hex color. text={}", text, error)
              null
            }
          }.toList()
        } else {
          emptyList()
        }

    if (escapeRanges.isEmpty() && colorRanges.isEmpty()) {
      return super.createSpans(capture, column, spanStyle)
    }

    val boundaries =
        buildSet {
              add(0)
              add(text.length)
              escapeRanges.forEach {
                add(it.first)
                add(it.last + 1)
              }
              colorRanges.forEach { (range, _) ->
                add(range.first)
                add(range.last + 1)
              }
            }
            .sorted()

    val spans = mutableListOf<Span>()
    for (index in 0 until boundaries.lastIndex) {
      val offset = boundaries[index]
      val segmentEnd = boundaries[index + 1]
      if (offset >= segmentEnd) continue

      val isEscape = escapeRanges.any { offset >= it.first && segmentEnd <= it.last + 1 }
      val color =
          if (isEscape) null
          else
              colorRanges
                  .firstOrNull { (range, _) ->
                    offset >= range.first && segmentEnd <= range.last + 1
                  }
                  ?.second

      val span =
          when {
            isEscape -> SpanFactory.obtain(column + offset, requireNotNull(escapeStyle))
            color != null -> {
              val textColor =
                  if (ColorUtils.calculateLuminance(color) > 0.5f) Color.BLACK else Color.WHITE
              SpanFactory.obtain(column + offset, requireNotNull(styleDef).makeStaticStyle()).also {
                it.setSpanExt(
                    SpanExtAttrs.EXT_COLOR_RESOLVER,
                    SpanConstColorResolver(textColor, color),
                )
              }
            }
            else -> SpanFactory.obtain(column + offset, spanStyle)
          }
      spans.add(span)
    }
    return spans
  }

  /**
   * Finds escape sequences in an ordinary Kotlin string literal. Triple-quoted strings are raw and
   * deliberately return no ranges. The grammar scanner exposes ordinary string contents as a single
   * `string_content` node, so these ranges cannot currently be expressed by a Tree-sitter query.
   */
  private fun findKotlinEscapeRanges(text: String): List<IntRange> {
    var quote = 0
    while (quote < text.length && text[quote] == '$') quote++
    if (quote >= text.length || text[quote] != '"') return emptyList()
    if (text.startsWith("\"\"\"", quote)) return emptyList()

    val ranges = mutableListOf<IntRange>()
    var index = quote + 1
    while (index < text.lastIndex) {
      if (text[index] != '\\') {
        index++
        continue
      }

      val endExclusive =
          if (
              index + 5 < text.length &&
                  text[index + 1] == 'u' &&
                  text.substring(index + 2, index + 6).all {
                    it.isDigit() || it.lowercaseChar() in 'a'..'f'
                  }
          ) {
            index + 6
          } else {
            (index + 2).coerceAtMost(text.length)
          }
      ranges.add(index until endExclusive)
      index = endExclusive
    }
    return ranges
  }
}
