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
import com.tom.rv2ide.lsp.xml.diagnostics.XmlElementDiagnosticRule
import org.eclipse.lemminx.dom.DOMElement

/** XML002-XML004: common attribute and namespace checks for every well-formed element. */
internal object CommonXmlElementDiagnosticRule : XmlElementDiagnosticRule {
  override val id: String = "common-xml-element"

  override fun supports(context: XmlDiagnosticContext): Boolean = true

  override fun diagnose(
      element: DOMElement,
      context: XmlDiagnosticContext,
      collector: XmlDiagnosticCollector,
  ) {
    checkDuplicateAttributes(element, collector)
    checkUndeclaredAttributePrefixes(element, collector)
    checkAndroidNamespace(element, collector)
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
        collector.warning(
            code = CODE_INVALID_ANDROID_NAMESPACE,
            message = "The android namespace must be '$ANDROID_NAMESPACE_URI'",
            attribute = attribute,
        )
      }
    }
  }

  private fun isNamespaceDeclaration(name: String): Boolean {
    return name == XMLNS_ATTRIBUTE || name.startsWith(XMLNS_PREFIX)
  }

  private const val CODE_DUPLICATE_ATTRIBUTE = "XML002"
  private const val CODE_UNDECLARED_NAMESPACE = "XML003"
  private const val CODE_INVALID_ANDROID_NAMESPACE = "XML004"
  private const val XMLNS_ATTRIBUTE = "xmlns"
  private const val XMLNS_PREFIX = "xmlns:"
  private const val ANDROID_NAMESPACE_DECLARATION = "xmlns:android"
  private const val ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android"
}