/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.xml.diagnostics

import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.DiagnosticSeverity.ERROR
import com.tom.rv2ide.lsp.models.DiagnosticSeverity.WARNING
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import org.eclipse.lemminx.dom.DOMAttr
import org.eclipse.lemminx.dom.DOMElement

/** Collects, bounds, deduplicates and sorts diagnostics produced during one document pass. */
internal class XmlDiagnosticCollector(private val text: String) {
  private val diagnostics = mutableListOf<DiagnosticItem>()
  private val keys = HashSet<String>()

  fun error(code: String, message: String, attribute: DOMAttr) {
    add(code, message, ERROR, attribute)
  }

  fun warning(code: String, message: String, attribute: DOMAttr) {
    add(code, message, WARNING, attribute)
  }

  fun errorTag(code: String, message: String, element: DOMElement) {
    addTag(code, message, ERROR, element)
  }

  fun warningTag(code: String, message: String, element: DOMElement) {
    addTag(code, message, WARNING, element)
  }

  private fun addTag(
      code: String,
      message: String,
      severity: DiagnosticSeverity,
      element: DOMElement,
  ) {
    val tagName = element.tagName ?: return
    val start = (element.start + 1).coerceIn(0, text.length)
    val end = (start + tagName.length).coerceIn(start, text.length)
    add(code, message, severity, start, end)
  }

  fun errorRange(code: String, message: String, start: Int, end: Int) {
    val safeStart = start.coerceIn(0, text.length)
    val safeEnd = end.coerceIn(safeStart, text.length)
    add(code, message, ERROR, safeStart, safeEnd)
  }

  fun errorValue(code: String, message: String, attribute: DOMAttr) {
    val valueRange = attribute.nodeAttrValue ?: return
    var start = valueRange.start.coerceIn(0, text.length)
    var end = valueRange.end.coerceIn(start, text.length)
    if (end - start >= 2 && text[start] in QUOTES && text[end - 1] == text[start]) {
      start++
      end--
    }
    add(code, message, ERROR, start, end)
  }

  private fun add(
      code: String,
      message: String,
      severity: DiagnosticSeverity,
      attribute: DOMAttr,
  ) {
    val nameRange = attribute.nodeAttrName ?: return
    val start = nameRange.start.coerceIn(0, text.length)
    val end = nameRange.end.coerceIn(start, text.length)
    add(code, message, severity, start, end)
  }

  private fun add(
      code: String,
      message: String,
      severity: DiagnosticSeverity,
      start: Int,
      end: Int,
  ) {
    val key = "$code:$start:$end"
    if (key in keys || diagnostics.size >= MAX_DIAGNOSTICS_PER_FILE) {
      return
    }
    keys.add(key)
    diagnostics +=
        DiagnosticItem(
            message = message,
            code = code,
            range = Range(offsetToPosition(start), offsetToPosition(end)),
            source = XmlDiagnosticsService.SOURCE,
            severity = severity,
        )
  }

  fun build(): List<DiagnosticItem> = diagnostics.sortedWith(DiagnosticItem.START_COMPARATOR)

  private fun offsetToPosition(offset: Int): Position {
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

  private companion object {
    const val MAX_DIAGNOSTICS_PER_FILE = 100
    const val QUOTES = "\"'"
  }
}