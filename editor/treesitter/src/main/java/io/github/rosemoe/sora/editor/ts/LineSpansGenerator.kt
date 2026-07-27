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

/**
 * ****************************************************************************
 * sora-editor - the awesome code editor for Android https://github.com/Rosemoe/sora-editor
 * Copyright (C) 2020-2023 Rosemoe
 *
 * This library is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA
 *
 * Please contact Rosemoe by email 2073412493@qq.com if you need additional information or have any
 * questions
 * ****************************************************************************
 */
package io.github.rosemoe.sora.editor.ts

import com.itsaky.androidide.treesitter.TSInputEdit
import com.itsaky.androidide.treesitter.TSQueryCapture
import com.itsaky.androidide.treesitter.TSQueryCursor
import com.itsaky.androidide.treesitter.TSTree
import com.tom.rv2ide.treesitter.api.TreeSitterQueryCapture
import com.tom.rv2ide.treesitter.api.safeExecQueryCursor
import io.github.rosemoe.sora.editor.ts.spans.TsSpanFactory
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.Spans
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Spans generator for tree-sitter. Results are cached.
 *
 * Note that this implementation does not support external modifications.
 *
 * Unlike Sora Editor 0.23.7 and its current main implementation, this integration composes
 * overlapping query captures into non-overlapping output spans. Narrow nested captures override
 * parents while identical ranges preserve query order.
 *
 * @author Rosemoe
 */
class LineSpansGenerator(
    internal var tree: TSTree,
    internal var lineCount: Int,
    private val content: Content,
    internal var theme: TsTheme,
    private val languageSpec: TsLanguageSpec,
    var scopedVariables: TsScopedVariables,
    private val spanFactory: TsSpanFactory,
) : Spans {

  companion object {

    const val CACHE_THRESHOLD = 60
  }

  private val caches = mutableListOf<SpanCache>()

  fun edit(edit: TSInputEdit) {
    tree.edit(edit)
  }

  fun queryCache(line: Int): MutableList<Span>? {
    for (i in 0 until caches.size) {
      val cache = caches[i]
      if (cache.line == line) {
        caches.removeAt(i)
        caches.add(0, cache)
        return cache.spans
      }
    }
    return null
  }

  fun pushCache(line: Int, spans: MutableList<Span>) {
    while (caches.size >= CACHE_THRESHOLD) {
      caches.removeAt(caches.size - 1)
    }
    caches.add(0, SpanCache(spans, line))
  }

  fun captureRegion(startIndex: Int, endIndex: Int): MutableList<Span> {
    val list = mutableListOf<Span>()

    if (!tree.canAccess()) {
      list.add(emptySpan(0))
      return list
    }

    val captures = mutableListOf<TSQueryCapture>()

    TSQueryCursor.create().use { cursor ->
      cursor.setByteRange(startIndex * 2, endIndex * 2)

      cursor.safeExecQueryCursor(
          query = languageSpec.tsQuery,
          tree = tree,
          recycleNodeAfterUse = true,
          debugLogging = false,
          debugName = "LineSpansGenerator.captureRegion()",
      ) { match ->
        if (languageSpec.queryPredicator.doPredicate(languageSpec.predicates, content, match)) {
          captures.addAll(match.captures)
        }
      }

      val regionLength = (endIndex - startIndex).coerceAtLeast(0)
      val styledRanges = mutableListOf<StyledRange>()

      // Preserve query order for captures with identical ranges. Nested captures are handled
      // separately below so that a narrower child can override its parent and the parent style can
      // be restored after the child ends.
      try {
        for ((order, capture) in captures.withIndex()) {
          val startByte = capture.node.startByte
          val endByte = capture.node.endByte
          val pattern = capture.index
          if (
              endByte / 2 <= startIndex ||
                  startByte / 2 >= endIndex ||
                  pattern in languageSpec.localsScopeIndices ||
                  pattern in languageSpec.localsDefinitionIndices ||
                  pattern in languageSpec.localsDefinitionValueIndices ||
                  pattern in languageSpec.localsMembersScopeIndices
          ) {
            continue
          }

          var style = 0L
          if (pattern in languageSpec.localsReferenceIndices) {
            val def =
                scopedVariables.findDefinition(
                    startByte / 2,
                    endByte / 2,
                    content.substring(startByte / 2, endByte / 2),
                )
            if (def != null && def.matchedHighlightPattern != -1) {
              style = theme.resolveStyleForPattern(def.matchedHighlightPattern)
            }
            // Let a regular highlight capture style unresolved references.
            if (style == 0L) {
              continue
            }
          }
          if (style == 0L) {
            style = theme.resolveStyleForPattern(pattern)
          }
          if (style == 0L) {
            style = theme.normalTextStyle
          }

          val start = (startByte / 2 - startIndex).coerceIn(0, regionLength)
          val end = (endByte / 2 - startIndex).coerceIn(0, regionLength)
          if (start >= end) {
            continue
          }

          val spans = createSpans(capture, start, end, style)
          for (index in spans.indices) {
            val span = spans[index]
            val spanStart = span.column.coerceIn(start, end)
            val spanEnd = (spans.getOrNull(index + 1)?.column ?: end).coerceIn(spanStart, end)
            if (spanStart < spanEnd) {
              styledRanges.add(
                  StyledRange(
                      start = spanStart,
                      end = spanEnd,
                      captureWidth = end - start,
                      order = order,
                      template = span,
                  )
              )
            } else {
              span.recycle()
            }
          }

        }

        val boundaries =
            buildSet {
                  add(0)
                  add(regionLength)
                  styledRanges.forEach {
                    add(it.start)
                    add(it.end)
                  }
                }
                .sorted()

        var previous: StyledRange? = null
        var emitted = false
        for (index in 0 until boundaries.lastIndex) {
          val segmentStart = boundaries[index]
          val segmentEnd = boundaries[index + 1]
          if (segmentStart >= segmentEnd) {
            continue
          }

          val winner =
              styledRanges
                  .asSequence()
                  .filter { it.start <= segmentStart && it.end >= segmentEnd }
                  // Narrower captures are more specific. Identical ranges preserve query order.
                  .minWithOrNull(compareBy<StyledRange> { it.captureWidth }.thenBy { it.order })

          if (!emitted || winner !== previous) {
            if (winner == null) {
              list.add(emptySpan(segmentStart))
            } else {
              list.add(winner.template.copy().also { it.column = segmentStart })
            }
            previous = winner
            emitted = true
          }
        }
      } finally {
        styledRanges.forEach { it.template.recycle() }
        captures.forEach { (it as? TreeSitterQueryCapture)?.recycle() }
      }

      if (list.isEmpty()) {
        list.add(emptySpan(0))
      }
    }
    if (list.isEmpty()) {
      list.add(emptySpan(0))
    }
    return list
  }

  private fun createSpans(
      capture: TSQueryCapture,
      startColumn: Int,
      endColumn: Int,
      style: Long,
  ): List<Span> {
    val spans = spanFactory.createSpans(capture, startColumn, style)
    try {
      var previousColumn: Int? = null
      for (span in spans) {
        val column = span.column
        if (column < startColumn || column > endColumn) {
          throw IndexOutOfBoundsException(
              "Span's column is out of bounds! column=$column, " +
                  "startColumn=$startColumn, endColumn=$endColumn"
          )
        }
        if (previousColumn != null && column <= previousColumn) {
          throw IllegalStateException(
              "Spans must not overlap! prevCol=$previousColumn, col=$column"
          )
        }
        previousColumn = column
      }
      return spans
    } catch (error: Throwable) {
      spans.forEach { it.recycle() }
      throw error
    }
  }

  private fun emptySpan(column: Int): Span {
    return SpanFactory.obtain(column, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL))
  }

  override fun adjustOnInsert(start: CharPosition, end: CharPosition) {}

  override fun adjustOnDelete(start: CharPosition, end: CharPosition) {}

  override fun read() =
      object : Spans.Reader {

        private var spans = mutableListOf<Span>()

        override fun moveToLine(line: Int) {
          try {
            if (line < 0 || line >= lineCount) {
              spans = mutableListOf()
              return
            }
            val cached = queryCache(line)
            if (cached != null) {
              spans = cached
              return
            }
            val start = content.indexer.getCharPosition(line, 0).index
            val end = start + content.getColumnCount(line)
            spans = captureRegion(start, end)
            pushCache(line, spans)
          } catch (err: Throwable) {
            err.printStackTrace()
          }
        }

        override fun getSpanCount() = spans.size

        override fun getSpanAt(index: Int) = spans[index]

        override fun getSpansOnLine(line: Int): MutableList<Span> {
          try {
            val cached = queryCache(line)
            if (cached != null) {
              return ArrayList(cached)
            }
            val start = content.indexer.getCharPosition(line, 0).index
            val end = start + content.getColumnCount(line)
            return captureRegion(start, end)
          } catch (err: Throwable) {
            err.printStackTrace()
            throw err
          }
        }
      }

  override fun supportsModify() = false

  override fun modify(): Spans.Modifier {
    throw UnsupportedOperationException()
  }

  override fun getLineCount() = lineCount
}

private data class StyledRange(
    val start: Int,
    val end: Int,
    val captureWidth: Int,
    val order: Int,
    val template: Span,
)

data class SpanCache(val spans: MutableList<Span>, val line: Int)
