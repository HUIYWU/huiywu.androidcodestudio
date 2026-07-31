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

import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.util.setupLookupForCompletion
import com.tom.rv2ide.projects.FileManager
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
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
  // Rule-specific state is owned by registered diagnostic rules.


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
        try {
          rule.diagnose(context, collector)
        } catch (error: Exception) {
          if (error is CancellationException) throw error
          log.warn("XML document diagnostic rule '{}' failed for {}", rule.id, file, error)
        }
      }
    }
    val elementRecoveryRules =
        XmlDiagnosticRuleRegistry.elementRecoveryRules.filter { it.supports(context) }
    val elementRules = XmlDiagnosticRuleRegistry.elementRules.filter { it.supports(context) }
    val textRules = XmlDiagnosticRuleRegistry.textRules.filter { it.supports(context) }
    visit(context.document, collector, context, elementRecoveryRules, elementRules, textRules)

    return DiagnosticResult(context.file, collector.build(), CHANNEL)
  }

  private fun visit(
      node: DOMNode,
      collector: XmlDiagnosticCollector,
      context: XmlDiagnosticContext,
      elementRecoveryRules: List<XmlElementRecoveryDiagnosticRule>,
      elementRules: List<XmlElementDiagnosticRule>,
      textRules: List<XmlTextDiagnosticRule>,
  ) {
    if (node is DOMElement) {
      val shouldSuppress =
          elementRecoveryRules.any { rule ->
            try {
              rule.diagnoseAndShouldSuppress(node, context, collector)
            } catch (error: Exception) {
              if (error is CancellationException) throw error
              log.warn("XML recovery diagnostic rule '{}' failed for {}", rule.id, context.file, error)
              false
            }
          }
      if (!shouldSuppress) {
        elementRules.forEach { rule ->
          try {
            rule.diagnose(node, context, collector)
          } catch (error: Exception) {
            if (error is CancellationException) throw error
            log.warn("XML element diagnostic rule '{}' failed for {}", rule.id, context.file, error)
          }
        }
      }
    } else if (node is DOMText && node.isText) {
      textRules.forEach { rule ->
        try {
          rule.diagnose(node, context, collector)
        } catch (error: Exception) {
          if (error is CancellationException) throw error
          log.warn("XML text diagnostic rule '{}' failed for {}", rule.id, context.file, error)
        }
      }
    }

    node.children.forEach { child ->
      visit(child, collector, context, elementRecoveryRules, elementRules, textRules)
    }
  }

  /** Compatibility entry point retained for focused local-ID tests. */
  internal fun collectLocalIdDeclarations(document: DOMNode): Set<String> =
      com.tom.rv2ide.lsp.xml.diagnostics.collectLocalIdDeclarations(document)

  companion object {
    private val log = LoggerFactory.getLogger(XmlDiagnosticsService::class.java)

    const val CHANNEL = "xml-lsp"
    const val SOURCE = "xml-lsp"
    const val ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android"
  }
}
