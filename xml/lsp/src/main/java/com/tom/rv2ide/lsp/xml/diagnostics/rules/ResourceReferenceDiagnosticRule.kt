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

import com.android.aaptcompiler.AaptResourceType.ID
import com.tom.rv2ide.lsp.xml.diagnostics.MissingFrameworkResourcePrefixDiagnosticData
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticCollector
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticContext
import com.tom.rv2ide.lsp.xml.diagnostics.XmlElementDiagnosticRule
import com.tom.rv2ide.lsp.xml.diagnostics.XmlResourceReference
import com.tom.rv2ide.lsp.xml.diagnostics.XmlResourceResolver
import com.tom.rv2ide.lsp.xml.diagnostics.XmlTextDiagnosticRule
import org.eclipse.lemminx.dom.DOMElement
import org.eclipse.lemminx.dom.DOMText

/** AXML003: conservative complete resource-reference checks for attributes and plain text nodes. */
internal object ResourceReferenceDiagnosticRule :
    XmlElementDiagnosticRule, XmlTextDiagnosticRule {
  override val id: String = "resource-reference"

  private val resourceResolver = XmlResourceResolver()

  override fun supports(context: XmlDiagnosticContext): Boolean = true

  override fun diagnose(
      element: DOMElement,
      context: XmlDiagnosticContext,
      collector: XmlDiagnosticCollector,
  ) {
    element.attributeNodes.orEmpty().forEach { attribute ->
      if (shouldSkipAttribute(attribute.namespaceURI)) {
        return@forEach
      }
      val value = attribute.value ?: return@forEach
      if (value.startsWith("@{") ||
          value.startsWith("@={") ||
          XmlResourceReference.isSpecialValue(value)) {
        return@forEach
      }

      val reference = XmlResourceReference.parse(value) ?: return@forEach
      if (isDeclaredLocally(reference, context.declaredIds)) {
        return@forEach
      }
      val resolution = resourceResolver.resolve(reference, context.moduleResourceIds)
      if (resolution == XmlResourceResolver.Resolution.NotFound) {
        val fix = frameworkPrefixFix(reference)
        collector.errorValue(
            code = CODE_UNRESOLVED_RESOURCE,
            message = "Cannot resolve resource reference '${reference.text}'",
            attribute = attribute,
            extra = fix ?: Any(),
        )
      }
    }
  }

  override fun diagnose(
      text: DOMText,
      context: XmlDiagnosticContext,
      collector: XmlDiagnosticCollector,
  ) {
    val candidate = textResourceReferenceCandidate(text) ?: return
    val reference = candidate.reference
    if (isDeclaredLocally(reference, context.declaredIds)) {
      return
    }
    if (resourceResolver.resolve(reference, context.moduleResourceIds) !=
        XmlResourceResolver.Resolution.NotFound) {
      return
    }

    val fix = frameworkPrefixFix(reference)
    collector.errorRange(
        code = CODE_UNRESOLVED_RESOURCE,
        message = "Cannot resolve resource reference '${reference.text}'",
        start = candidate.start,
        end = candidate.end,
        extra = fix ?: Any(),
    )
  }

  internal fun textResourceReferenceCandidate(textNode: DOMText): TextResourceReferenceCandidate? {
    // CDATA extends DOMText. Retain the exact TEXT_NODE check to avoid treating CDATA as Android
    // resource syntax; comments and processing instructions are different node types too.
    if (!textNode.isText) {
      return null
    }
    val rawText = textNode.data
    val value = rawText.trim()
    if (value.isEmpty() ||
        value.startsWith("@{") ||
        value.startsWith("@={") ||
        XmlResourceReference.isSpecialValue(value)) {
      return null
    }
    val reference = XmlResourceReference.parse(value) ?: return null
    val leadingWhitespace = rawText.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    val start = textNode.startContent + leadingWhitespace
    return TextResourceReferenceCandidate(reference, start, start + value.length)
  }

  internal fun shouldSkipAttribute(namespaceUri: String?): Boolean {
    return namespaceUri == TOOLS_NAMESPACE_URI
  }

  private fun frameworkPrefixFix(
      reference: XmlResourceReference,
  ): MissingFrameworkResourcePrefixDiagnosticData? {
    if (reference.packageName != null || reference.isThemeAttribute || reference.type == ID) {
      return null
    }
    val frameworkReference =
        XmlResourceReference.parse("@android:${reference.type.tagName}/${reference.entry}") ?: return null
    if (resourceResolver.resolve(frameworkReference) != XmlResourceResolver.Resolution.Resolved) {
      return null
    }
    return MissingFrameworkResourcePrefixDiagnosticData(reference.text, frameworkReference.text)
  }

  private fun isDeclaredLocally(
      reference: XmlResourceReference,
      declaredIds: Set<String>,
  ): Boolean {
    return reference.packageName == null &&
        !reference.isThemeAttribute &&
        reference.type == ID &&
        reference.entry in declaredIds
  }

  internal data class TextResourceReferenceCandidate(
      val reference: XmlResourceReference,
      val start: Int,
      val end: Int,
  )

  private const val CODE_UNRESOLVED_RESOURCE = "AXML003"
  private const val TOOLS_NAMESPACE_URI = "http://schemas.android.com/tools"
}