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

import com.android.aaptcompiler.AaptResourceType.ID
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.util.setupLookupForCompletion
import com.tom.rv2ide.projects.FileManager
import java.nio.file.Path
import org.eclipse.lemminx.dom.DOMElement
import org.eclipse.lemminx.dom.DOMNode
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.dom.DOMText
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager
import org.slf4j.LoggerFactory

/**
 * Produces lightweight, document-local XML diagnostics.
 *
 * XML structure rules are always safe to run. Resource-reference checks use already-published
 * completion resource tables and treat unavailable tables as unknown, so they remain safe before a
 * Gradle project has been initialized.
 */
internal class XmlDiagnosticsService {

  private val resourceResolver = XmlResourceResolver()

  fun analyze(file: Path): DiagnosticResult {
    // Diagnostics use the same resource and widget snapshots as completion. Without this setup,
    // Android-specific rules only become eligible after another feature or tooling warm-up happens
    // to populate Lookup, making diagnostics appear to depend on a build.
    runCatching { setupLookupForCompletion(file) }
        .onFailure { error -> log.debug("Unable to prepare XML diagnostic lookup for {}", file, error) }

    // Active editor documents may contain unsaved changes. FileManager returns that in-memory
    // snapshot when present and falls back to disk for inactive files.
    val text = runCatching { FileManager.getDocumentContents(file) }.getOrElse { error ->
      log.warn("Unable to read XML file for diagnostics: {}", file, error)
      return DiagnosticResult(file, emptyList(), CHANNEL)
    }

    val document =
        runCatching {
              DOMParser.getInstance()
                  .parse(text, ANDROID_NAMESPACE_URI, URIResolverExtensionManager())
            }
            .getOrElse { error ->
              log.warn("Unable to parse XML file for diagnostics: {}", file, error)
              return DiagnosticResult(file, emptyList(), CHANNEL)
            }
    val context = XmlDiagnosticContext.create(file, text, document)
    val collector = XmlDiagnosticCollector(context.text)
    XmlDiagnosticRuleRegistry.documentRules.forEach { rule ->
      if (rule.supports(context)) {
        rule.diagnose(context, collector)
      }
    }
    val elementRules = XmlDiagnosticRuleRegistry.elementRules.filter { it.supports(context) }
    visit(context.document, collector, context, elementRules)

    return DiagnosticResult(context.file, collector.build(), CHANNEL)
  }

  private fun visit(
      node: DOMNode,
      collector: XmlDiagnosticCollector,
      context: XmlDiagnosticContext,
      elementRules: List<XmlElementDiagnosticRule>,
  ) {
    if (node is DOMElement) {
      val hasSyntaxRecovery = checkSyntaxRecovery(node, collector)
      if (!hasSyntaxRecovery) {
        elementRules.forEach { rule -> rule.diagnose(node, context, collector) }
        checkResourceReferences(node, collector, context.declaredIds)
      }
    } else if (node is DOMText && node.isText) {
      checkTextResourceReference(node, collector, context.declaredIds)
    }

    node.children.forEach { child ->
      visit(child, collector, context, elementRules)
    }
  }

  /** Compatibility entry point retained for focused local-ID tests. */
  internal fun collectLocalIdDeclarations(document: DOMNode): Set<String> =
      com.tom.rv2ide.lsp.xml.diagnostics.collectLocalIdDeclarations(document)

  /**
   * Reports only recovery states explicitly represented by LemMinX's tolerant DOM. This is not a
   * substitute for a full parser-error stream, but it gives stable ranges for common structural
   * failures without attempting to re-parse XML using regular expressions.
   */
  private fun checkSyntaxRecovery(element: DOMElement, collector: XmlDiagnosticCollector): Boolean {
    val tagName = element.tagName
    if (element.isOrphanEndTag) {
      collector.errorRange(
          code = CODE_XML_SYNTAX,
          message = if (tagName == null) "Unexpected closing tag" else "Unexpected closing tag '</$tagName>'",
          start = element.start,
          end = element.end,
      )
      return true
    }
    if (!element.hasStartTag() || tagName == null) {
      return false
    }
    // LemMinX records a `/>` token as self-closed but may leave startTagCloseOffset unset.
    // A self-closed element is nevertheless syntactically complete and must not be diagnosed as
    // an unclosed start tag.
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
    if (!element.isSelfClosed && !element.isClosed && !hasDirectSyntaxRecoveryChild(element)) {
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

  private fun hasDirectSyntaxRecoveryChild(element: DOMElement): Boolean {
    return element.children.any { child ->
      child is DOMElement &&
          (child.isOrphanEndTag ||
              (child.hasStartTag() &&
                  !child.isSelfClosed &&
                  (!child.isStartTagClosed || !child.isClosed)))
    }
  }

  private fun checkTextResourceReference(
      textNode: DOMText,
      collector: XmlDiagnosticCollector,
      declaredIds: Set<String>,
  ) {
    val candidate = textResourceReferenceCandidate(textNode) ?: return
    val reference = candidate.reference
    if (reference.packageName == null && !reference.isThemeAttribute &&
        reference.type == ID && reference.entry in declaredIds) {
      return
    }
    if (resourceResolver.resolve(reference) != XmlResourceResolver.Resolution.NotFound) {
      return
    }

    collector.errorRange(
        code = CODE_UNRESOLVED_RESOURCE,
        message = "Cannot resolve resource reference '${reference.text}'",
        start = candidate.start,
        end = candidate.end,
    )
  }

  internal fun textResourceReferenceCandidate(textNode: DOMText): TextResourceReferenceCandidate? {
    // CDATA extends DOMText, so retain the exact TEXT_NODE check to avoid interpreting CDATA as
    // Android resource syntax. Comments and processing instructions are different node types too.
    if (!textNode.isText) {
      return null
    }
    val rawText = textNode.data
    val value = rawText.trim()
    if (value.isEmpty() || value.startsWith("@{") || value.startsWith("@={") ||
        XmlResourceReference.isSpecialValue(value)) {
      return null
    }
    val reference = XmlResourceReference.parse(value) ?: return null
    val leadingWhitespace = rawText.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    val start = textNode.startContent + leadingWhitespace
    return TextResourceReferenceCandidate(reference, start, start + value.length)
  }

  internal data class TextResourceReferenceCandidate(
      val reference: XmlResourceReference,
      val start: Int,
      val end: Int,
  )

  internal fun shouldSkipResourceReferenceAttribute(namespaceUri: String?): Boolean {
    return namespaceUri == TOOLS_NAMESPACE_URI
  }

  private fun checkResourceReferences(
      element: DOMElement,
      collector: XmlDiagnosticCollector,
      declaredIds: Set<String>,
  ) {
    element.attributeNodes.orEmpty().forEach { attribute ->
      if (shouldSkipResourceReferenceAttribute(attribute.namespaceURI)) {
        return@forEach
      }
      val value = attribute.value ?: return@forEach
      if (value.startsWith("@{") || value.startsWith("@={") ||
          XmlResourceReference.isSpecialValue(value)) {
        return@forEach
      }

      val reference = XmlResourceReference.parse(value) ?: return@forEach
      if (reference.packageName == null && !reference.isThemeAttribute &&
          reference.type == ID && reference.entry in declaredIds) {
        return@forEach
      }
      if (resourceResolver.resolve(reference) == XmlResourceResolver.Resolution.NotFound) {
        collector.errorValue(
            code = CODE_UNRESOLVED_RESOURCE,
            message = "Cannot resolve resource reference '${reference.text}'",
            attribute = attribute,
        )
      }
    }
  }

  companion object {
    private val log = LoggerFactory.getLogger(XmlDiagnosticsService::class.java)

    const val CHANNEL = "xml-lsp"
    const val SOURCE = "xml-lsp"
    const val CODE_XML_SYNTAX = "XML001"
    const val CODE_UNRESOLVED_RESOURCE = "AXML003"
    const val ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android"
    const val TOOLS_NAMESPACE_URI = "http://schemas.android.com/tools"
  }
}
