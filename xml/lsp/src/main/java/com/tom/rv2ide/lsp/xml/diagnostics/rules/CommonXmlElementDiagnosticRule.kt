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

import com.tom.rv2ide.lsp.xml.diagnostics.ClosingTagMismatchDiagnosticData
import com.tom.rv2ide.lsp.xml.diagnostics.InvalidAndroidNamespaceDiagnosticData
import com.tom.rv2ide.lsp.xml.diagnostics.MissingAndroidNamespaceDiagnosticData
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticCollector
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticContext
import com.tom.rv2ide.lsp.xml.diagnostics.XmlElementDiagnosticRule
import com.tom.rv2ide.lsp.xml.diagnostics.XmlElementRecoveryDiagnosticRule
import org.eclipse.lemminx.dom.DOMElement

/** XML001-XML004: tolerant recovery, attribute and namespace checks for every XML element. */
internal object CommonXmlElementDiagnosticRule :
    XmlElementRecoveryDiagnosticRule, XmlElementDiagnosticRule {
  override val id: String = "common-xml-element"

  override fun supports(context: XmlDiagnosticContext): Boolean = true

  override fun diagnoseAndShouldSuppress(
      element: DOMElement,
      context: XmlDiagnosticContext,
      collector: XmlDiagnosticCollector,
  ): Boolean {
    val tagName = element.tagName
    // Xerces has already identified the first unmatched closing name precisely. LemMinX's
    // recovered tree cannot preserve that stack relation and otherwise emits cascade XML001s.
    if (collector.hasExtra(CODE_XML_PARSER_SYNTAX, ClosingTagMismatchDiagnosticData::class.java)) {
      return true
    }
    if (element.isOrphanEndTag) {
      collector.errorRange(
          code = CODE_XML_SYNTAX,
          message =
              if (tagName == null) "Unexpected closing tag"
              else "Unexpected closing tag '</$tagName>'",
          start = element.start,
          end = element.end,
      )
      return true
    }
    if (!element.hasStartTag() || tagName == null) {
      return false
    }
    // LemMinX can represent `/>` as self-closed without setting startTagCloseOffset.
    if (element.isSelfClosed) {
      return false
    }
    if (!element.isStartTagClosed) {
      collector.errorRange(
          code = CODE_XML_SYNTAX,
          message = "Start tag '<$tagName>' is not closed",
          start = element.start,
          end = element.unclosedStartTagCloseOffset,
      )
      return true
    }
    if (!element.isClosed && !hasDirectSyntaxRecoveryChild(element)) {
      val nameStart = element.start + 1
      collector.errorRange(
          code = CODE_XML_SYNTAX,
          message = "Element '<$tagName>' is missing an end tag",
          start = nameStart,
          end = nameStart + tagName.length,
      )
      return true
    }
    return false
  }

  override fun diagnose(
      element: DOMElement,
      context: XmlDiagnosticContext,
      collector: XmlDiagnosticCollector,
  ) {
    checkDuplicateAttributes(element, collector)
    checkUndeclaredAttributePrefixes(element, collector)
    checkAndroidNamespace(element, collector)
  }

  private fun hasDirectSyntaxRecoveryChild(element: DOMElement): Boolean {
    return element.children.any { child ->
      child is DOMElement &&
          (child.isOrphanEndTag ||
              (child.hasStartTag() &&
                  !child.isSelfClosed &&
                  (!child.isStartTagClosed || !child.isClosed)))
    }
  }

  private fun checkDuplicateAttributes(element: DOMElement, collector: XmlDiagnosticCollector) {
    val seen = HashSet<String>()
    element.attributeNodes.orEmpty().forEach { attribute ->
      val name = attribute.name ?: return@forEach
      if (!seen.add(name)) {
        collector.error(
            code = CODE_DUPLICATE_ATTRIBUTE,
            message = "Duplicate attribute '$name'",
            attribute = attribute,
        )
      }
    }
  }

  private fun checkUndeclaredAttributePrefixes(
      element: DOMElement,
      collector: XmlDiagnosticCollector,
  ) {
    element.attributeNodes.orEmpty().forEach { attribute ->
      val name = attribute.name ?: return@forEach
      if (isNamespaceDeclaration(name)) {
        return@forEach
      }
      val separator = name.indexOf(':')
      if (separator <= 0) {
        return@forEach
      }
      val prefix = name.substring(0, separator)
      if (element.getNamespaceURI(prefix) == null) {
        collector.error(
            code = CODE_UNDECLARED_NAMESPACE,
            message = "Namespace prefix '$prefix' is not declared",
            attribute = attribute,
            extra =
                if (prefix == ANDROID_NAMESPACE_PREFIX)
                    MissingAndroidNamespaceDiagnosticData(prefix)
                else Any(),
        )
      }
    }
  }

  private fun checkAndroidNamespace(element: DOMElement, collector: XmlDiagnosticCollector) {
    element.attributeNodes.orEmpty().forEach { attribute ->
      if (attribute.name != ANDROID_NAMESPACE_DECLARATION) {
        return@forEach
      }
      if (attribute.value != ANDROID_NAMESPACE_URI) {
        // The quick fix replaces only the URI value, not the xmlns:android attribute name.
        collector.warningValue(
            code = CODE_INVALID_ANDROID_NAMESPACE,
            message = "The android namespace must be '$ANDROID_NAMESPACE_URI'",
            attribute = attribute,
            extra = InvalidAndroidNamespaceDiagnosticData(
                actualUri = attribute.value.orEmpty(),
                expectedUri = ANDROID_NAMESPACE_URI,
            ),
        )
      }
    }
  }

  private fun isNamespaceDeclaration(name: String): Boolean {
    return name == XMLNS_ATTRIBUTE || name.startsWith(XMLNS_PREFIX)
  }

  private const val CODE_XML_SYNTAX = "XML001"
  private const val CODE_XML_PARSER_SYNTAX = "XML005"
  private const val CODE_DUPLICATE_ATTRIBUTE = "XML002"
  private const val CODE_UNDECLARED_NAMESPACE = "XML003"
  private const val CODE_INVALID_ANDROID_NAMESPACE = "XML004"
  private const val XMLNS_ATTRIBUTE = "xmlns"
  private const val XMLNS_PREFIX = "xmlns:"
  private const val ANDROID_NAMESPACE_PREFIX = "android"
  private const val ANDROID_NAMESPACE_DECLARATION = "xmlns:android"
  private const val ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android"
}