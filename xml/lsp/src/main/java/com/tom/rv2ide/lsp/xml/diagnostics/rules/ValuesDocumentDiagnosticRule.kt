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
package com.tom.rv2ide.lsp.xml.diagnostics.rules

import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticCollector
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticContext
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticRule
import org.eclipse.lemminx.dom.DOMElement

/** VALUES001-VALUES004: values root, name syntax and same-file duplicate checks. */
internal object ValuesDocumentDiagnosticRule : XmlDiagnosticRule {
  override val id: String = "values-document"

  override fun supports(context: XmlDiagnosticContext): Boolean = context.isValuesFile

  override fun diagnose(context: XmlDiagnosticContext, collector: XmlDiagnosticCollector) {
    val root = context.document.documentElement
    if (root == null || !root.isStartTagClosed || root.tagName != VALUES_ROOT_TAG) {
      root?.let {
        collector.errorTag(
            code = CODE_VALUES_ROOT,
            message = "The root element of a values resource file must be <$VALUES_ROOT_TAG>",
            element = it,
        )
      }
      return
    }

    val seen = HashSet<String>()
    root.children.filterIsInstance<DOMElement>().forEach { element ->
      if (!element.isStartTagClosed || element.tagName !in VALUES_NAMED_TAGS) {
        return@forEach
      }
      val nameAttribute = element.getAttributeNode(RESOURCE_NAME_ATTRIBUTE)
      val name = nameAttribute?.value
      if (nameAttribute == null || name.isNullOrBlank()) {
        collector.errorTag(
            code = CODE_VALUES_MISSING_NAME,
            message = "Resource <${element.tagName}> requires a '$RESOURCE_NAME_ATTRIBUTE' attribute",
            element = element,
        )
        return@forEach
      }
      if (!isValidResourceName(name)) {
        collector.errorValue(
            code = CODE_VALUES_INVALID_NAME,
            message = "'$name' is not a valid Android resource name",
            attribute = nameAttribute,
        )
        return@forEach
      }
      val key = "${element.tagName}:$name"
      if (!seen.add(key)) {
        collector.errorValue(
            code = CODE_VALUES_DUPLICATE_NAME,
            message = "Duplicate <${element.tagName}> resource name '$name' in this file",
            attribute = nameAttribute,
        )
      }
    }
  }

  /** Lightweight equivalent of AAPT's resource-entry-name check. */
  internal fun isValidResourceName(name: String): Boolean {
    if (name.isEmpty()) {
      return false
    }
    val first = name.codePointAt(0)
    if (!Character.isUnicodeIdentifierStart(first) && first != '_'.code) {
      return false
    }
    var offset = Character.charCount(first)
    while (offset < name.length) {
      val codePoint = name.codePointAt(offset)
      if (!Character.isUnicodeIdentifierPart(codePoint) && codePoint != '.'.code && codePoint != '-'.code) {
        return false
      }
      offset += Character.charCount(codePoint)
    }
    return true
  }

  private const val CODE_VALUES_ROOT = "VALUES001"
  private const val CODE_VALUES_MISSING_NAME = "VALUES002"
  private const val CODE_VALUES_INVALID_NAME = "VALUES003"
  private const val CODE_VALUES_DUPLICATE_NAME = "VALUES004"
  private const val VALUES_ROOT_TAG = "resources"
  private const val RESOURCE_NAME_ATTRIBUTE = "name"
  private val VALUES_NAMED_TAGS =
      setOf(
          "string",
          "color",
          "dimen",
          "bool",
          "integer",
          "fraction",
          "array",
          "integer-array",
          "string-array",
          "plurals",
          "style",
          "attr",
          "declare-styleable",
          "drawable",
          "font",
          "id",
          "macro",
      )
}