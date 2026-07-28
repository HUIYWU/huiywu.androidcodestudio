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
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.xml.providers

import com.android.aaptcompiler.ArrayResource
import com.android.aaptcompiler.AttributeResource
import com.android.aaptcompiler.BasicString
import com.android.aaptcompiler.BinaryPrimitive
import com.android.aaptcompiler.ConfigDescription
import com.android.aaptcompiler.FileReference
import com.android.aaptcompiler.Id
import com.android.aaptcompiler.Macro
import com.android.aaptcompiler.Plural
import com.android.aaptcompiler.RawString
import com.android.aaptcompiler.Reference
import com.android.aaptcompiler.ResourceConfigValue
import com.android.aaptcompiler.Style
import com.android.aaptcompiler.Styleable
import com.android.aaptcompiler.StyledString
import com.android.aaptcompiler.Value
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.MarkupKind
import com.tom.rv2ide.lsp.util.setupLookupForCompletion
import com.tom.rv2ide.lsp.xml.diagnostics.XmlResourceReference
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.xml.res.IResourceTable
import com.tom.rv2ide.xml.resources.ResourceTableRegistry
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager
import org.slf4j.LoggerFactory

/** Builds compact resource metadata for the editor's existing language-server hover popup. */
internal class XmlHoverProvider {

  fun hover(params: DefinitionParams): MarkupContent {
    params.cancelChecker.abortIfCancelled()
    runCatching { setupLookupForCompletion(params.file) }
        .onFailure { error -> log.debug("Unable to prepare XML hover lookup for {}", params.file, error) }

    val text =
        runCatching { FileManager.getDocumentContents(params.file) }.getOrElse { error ->
          log.debug("Unable to read XML file for hover: {}", params.file, error)
          return MarkupContent()
        }
    val offset = offsetAt(text, params.position.line, params.position.column)
        ?: return MarkupContent()
    val document =
        runCatching {
              DOMParser.getInstance().parse(text, ANDROID_NAMESPACE_URI, URIResolverExtensionManager())
            }
            .getOrElse { error ->
              log.debug("Unable to parse XML file for hover: {}", params.file, error)
              return MarkupContent()
            }

    params.cancelChecker.abortIfCancelled()
    val rawReference =
        XmlDefinitionProvider().referenceAt(document, text, offset) ?: return MarkupContent()
    val reference = XmlResourceReference.parse(rawReference) ?: return MarkupContent()
    val candidates = candidatesFor(reference, resourceTables(reference.packageName)) {
      params.cancelChecker.abortIfCancelled()
    }
    if (candidates.isEmpty()) return MarkupContent()
    return MarkupContent(formatHover(reference, candidates), MarkupKind.MARKDOWN)
  }

  internal fun candidatesFor(
      reference: XmlResourceReference,
      tables: Collection<IResourceTable>,
      checkCancelled: () -> Unit = {},
  ): List<ResourceHoverCandidate> {
    val result = mutableListOf<ResourceHoverCandidate>()
    val seen = mutableSetOf<String>()
    tables.forEach { table ->
      checkCancelled()
      table.packages.forEach packageLoop@ { resourcePackage ->
        if (
            (reference.packageName != null && resourcePackage.name != reference.packageName) ||
                (reference.packageName == null &&
                    resourcePackage.name == ResourceTableRegistry.PCK_ANDROID)
        ) {
          return@packageLoop
        }
        val entry =
            resourcePackage.findGroup(reference.type)?.findEntry(reference.entry)
                ?: return@packageLoop
        entry.values
            .sortedWith(
                compareBy<ResourceConfigValue>(
                    { it.config != ConfigDescription() },
                    { it.config.toString() },
                )
            )
            .forEach valueLoop@ { configured ->
              checkCancelled()
              val value = configured.value ?: return@valueLoop
              val source = value.source
              val key = "${resourcePackage.name}|${configured.config}|${source.path}|${source.line}|${summary(value)}"
              if (seen.add(key)) {
                result.add(
                    ResourceHoverCandidate(
                        packageName = resourcePackage.name,
                        configuration = configured.config.toString().ifBlank { "default" },
                        source = source.path,
                        line = source.line,
                        valueSummary = summary(value),
                    )
                )
              }
            }
      }
    }
    return result
  }

  internal fun formatHover(
      reference: XmlResourceReference,
      candidates: List<ResourceHoverCandidate>,
  ): String {
    val first = candidates.first()
    return buildString {
      append("```xml\n")
      append(reference.text)
      append("\n```\n\n")
      append("**Type:** `${reference.type.tagName}`  \n")
      append("**Package:** `${first.packageName.ifBlank { "current" }}`")
      candidates.take(MAX_CONFIGURATIONS).forEach { candidate ->
        append("\n\n---\n\n")
        append("**Configuration:** `${candidate.configuration}`")
        if (candidate.valueSummary != null) {
          append("  \n**Value:** `")
          append(candidate.valueSummary.escapeMarkdownCode())
          append('`')
        }
        if (candidate.source.isNotBlank()) {
          append("  \n**Source:** `")
          append(candidate.source.escapeMarkdownCode())
          candidate.line?.let { append(":$it") }
          append('`')
        }
      }
      if (candidates.size > MAX_CONFIGURATIONS) {
        append("\n\n_")
        append(candidates.size - MAX_CONFIGURATIONS)
        append(" more configurations_")
      }
    }
  }

  private fun resourceTables(packageName: String?): Set<IResourceTable> {
    val lookup = Lookup.getDefault()
    if (packageName == ResourceTableRegistry.PCK_ANDROID) {
      return lookup.lookup(ResourceTableRegistry.COMPLETION_FRAMEWORK_RES)?.let(::setOf).orEmpty()
    }
    return (lookup.lookup(ResourceTableRegistry.COMPLETION_MODULE_RES).orEmpty() +
            lookup.lookup(ResourceTableRegistry.COMPLETION_DEP_RES).orEmpty())
        .toSet()
  }

  private fun summary(value: Value): String? {
    val raw =
        when (value) {
          is BasicString -> value.toString()
          is StyledString -> value.toString()
          is RawString -> value.value.value()
          is FileReference -> value.path.value()
          is Reference -> value.name.toString()
          is Id -> "ID"
          is Style -> value.parent?.name?.toString()?.let { "parent=$it, ${value.entries.size} items" }
              ?: "${value.entries.size} items"
          is ArrayResource -> "${value.elements.size} items"
          is Plural -> "${value.values.count { it != null }} quantities"
          is Styleable -> "${value.entries.size} attributes"
          is AttributeResource -> "attribute format mask 0x${value.typeMask.toString(16)}"
          is Macro -> value.rawValue ?: "macro"
          is BinaryPrimitive -> value.resValue.toString()
          else -> null
        }
    return raw?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_SUMMARY_LENGTH)
  }

  internal fun offsetAt(text: String, line: Int, column: Int): Int? {
    if (line < 0 || column < 0) return null
    var offset = 0
    var currentLine = 0
    while (currentLine < line) {
      val newline = text.indexOf('\n', offset)
      if (newline < 0) return null
      offset = newline + 1
      currentLine++
    }
    val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
    return (offset + column).takeIf { it <= lineEnd }
  }

  private fun String.escapeMarkdownCode(): String = replace("`", "\\`")

  internal data class ResourceHoverCandidate(
      val packageName: String,
      val configuration: String,
      val source: String,
      val line: Int?,
      val valueSummary: String?,
  )

  private companion object {
    const val ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android"
    const val MAX_CONFIGURATIONS = 4
    const val MAX_SUMMARY_LENGTH = 160
    val log = LoggerFactory.getLogger(XmlHoverProvider::class.java)
  }
}