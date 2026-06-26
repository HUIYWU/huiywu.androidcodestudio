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
package com.tom.rv2ide.lsp.models

import com.tom.rv2ide.lsp.models.DiagnosticSeverity.ERROR
import com.tom.rv2ide.lsp.models.DiagnosticSeverity.HINT
import com.tom.rv2ide.lsp.models.DiagnosticSeverity.INFO
import com.tom.rv2ide.lsp.models.DiagnosticSeverity.WARNING
import com.tom.rv2ide.models.Range
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_ERROR
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_NONE
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_TYPO
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_WARNING
import java.nio.file.Path
import java.nio.file.Paths
import org.slf4j.LoggerFactory


data class DiagnosticItem(
    var message: String,
    var code: String,
    var range: Range,
    var source: String,
    var severity: DiagnosticSeverity,
) {

  var extra: Any = Any()
  companion object {
    @JvmField
    val START_COMPARATOR: Comparator<in DiagnosticItem> =
        Comparator.comparing(DiagnosticItem::range)

    private val log = LoggerFactory.getLogger("DiagnosticRegionMapping")
    private const val TRACE_FILE_NAME = "BattleActionConfig.java"
    private const val TRACE_MIN_LINE = 53
    private const val TRACE_MAX_LINE = 60

    private fun mapSeverity(severity: DiagnosticSeverity): Short {
      return when (severity) {
        ERROR -> SEVERITY_ERROR
        WARNING -> SEVERITY_WARNING
        INFO -> SEVERITY_NONE
        HINT -> SEVERITY_TYPO
      }
    }
  }


  fun asDiagnosticRegion(content: CharSequence): DiagnosticRegion {
    return asDiagnosticRegion(LineIndex.from(content))
  }
  fun asDiagnosticRegion(lineIndex: LineIndex): DiagnosticRegion {
    return try {
      val startIndex = lineIndex.lineColumnToIndex(range.start.line, range.start.column)
      val endIndex = lineIndex.lineColumnToIndex(range.end.line, range.end.column)

      if (shouldTraceDiagnostic()) {
        log.warn(
            "DIAG_TRACE region-map code={} severity={} rawRange=({}:{})-({}:{}) mapped=({},{})",
            code,
            severity,
            range.start.line,
            range.start.column,
            range.end.line,
            range.end.column,
            startIndex,
            endIndex,
        )
      }
      if (startIndex >= endIndex) {
        log.warn(
            "DIAG_TRACE suspicious-region code={} severity={} rawRange=({}:{})-({}:{}) mapped=({},{})",
            code,
            severity,
            range.start.line,
            range.start.column,
            range.end.line,
            range.end.column,
            startIndex,
            endIndex,
        )
      }

      DiagnosticRegion(startIndex, endIndex, mapSeverity(severity))
    } catch (e: Exception) {
      // Keep diagnostics publishing fail-soft. A single malformed diagnostic range must not prevent
      // the editor from receiving the rest of the diagnostics batch.
      log.warn(
          "DIAG_TRACE fallback-region code={} severity={} rawRange=({}:{})-({}:{}) fallback=(0,1)",
          code,
          severity,
          range.start.line,
          range.start.column,
          range.end.line,
          range.end.column,
          e,
      )
      DiagnosticRegion(0, 1, mapSeverity(severity))
    }
  }

  private fun shouldTraceDiagnostic(): Boolean {
    val startLine = range.start.line
    val endLine = range.end.line
    val sourceValue = source
    return (startLine in TRACE_MIN_LINE..TRACE_MAX_LINE || endLine in TRACE_MIN_LINE..TRACE_MAX_LINE) &&
        sourceValue.contains(TRACE_FILE_NAME)
  }

}

class LineIndex private constructor(
    private val lineStarts: IntArray,
    private val contentLength: Int,
) {

  fun lineColumnToIndex(line: Int, column: Int): Int {
    if (lineStarts.isEmpty()) {
      return 0
    }
    val safeLine = line.coerceIn(0, lineStarts.lastIndex)
    val safeColumn = column.coerceAtLeast(0)
    return (lineStarts[safeLine] + safeColumn).coerceIn(0, contentLength)
  }

  companion object {
    /**
     * Builds a reusable line-start table for a diagnostics publish batch. This avoids repeatedly
     * scanning a large editor buffer for every diagnostic range.
     */
    @JvmStatic
    fun from(content: CharSequence): LineIndex {
      val starts = ArrayList<Int>()
      starts.add(0)
      for (index in 0 until content.length) {
        if (content[index] == '\n') {
          starts.add(index + 1)
        }
      }
      return LineIndex(starts.toIntArray(), content.length)
    }
  }
}
data class DiagnosticResult(
    var file: Path,
    var diagnostics: List<DiagnosticItem>,
    var channel: String = DEFAULT_CHANNEL,
) {
  companion object {
    const val DEFAULT_CHANNEL = "default"
    const val CHANNEL_SERVER = "server"

    @JvmField val NO_UPDATE = DiagnosticResult(Paths.get(""), emptyList())
  }
}


enum class DiagnosticSeverity {
  ERROR,
  WARNING,
  INFO,
  HINT,
}
