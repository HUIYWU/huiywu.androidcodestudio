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

import com.android.aaptcompiler.ConfigDescription
import com.android.aaptcompiler.ResourceConfigValue
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.util.setupLookupForCompletion
import com.tom.rv2ide.lsp.xml.diagnostics.XmlResourceReference
import com.tom.rv2ide.models.Location
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.xml.res.IResourceTable
import com.tom.rv2ide.xml.resources.ResourceTableRegistry
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import org.eclipse.lemminx.dom.DOMAttr
import org.eclipse.lemminx.dom.DOMDocument
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.dom.DOMText
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager
import org.slf4j.LoggerFactory

/** Resolves complete Android resource references to their resource-table source locations. */
internal class XmlDefinitionProvider {

  fun findDefinition(params: DefinitionParams): DefinitionResult {
    params.cancelChecker.abortIfCancelled()

    runCatching { setupLookupForCompletion(params.file) }
        .onFailure { error -> log.debug("Unable to prepare XML definition lookup for {}", params.file, error) }

    val text =
        runCatching { FileManager.getDocumentContents(params.file) }
            .getOrElse { error ->
              log.debug("Unable to read XML file for definition lookup: {}", params.file, error)
              return DefinitionResult(emptyList())
            }
    val document =
        runCatching {
              DOMParser.getInstance().parse(text, ANDROID_NAMESPACE_URI, URIResolverExtensionManager())
            }
            .getOrElse { error ->
              log.debug("Unable to parse XML file for definition lookup: {}", params.file, error)
              return DefinitionResult(emptyList())
            }

    params.cancelChecker.abortIfCancelled()
    val cursor = params.position.requireIndex()
    if (cursor < 0 || cursor > text.length) {
      return DefinitionResult(emptyList())
    }
    val candidate = referenceAt(document, text, cursor) ?: return DefinitionResult(emptyList())
    val reference = XmlResourceReference.parse(candidate) ?: return DefinitionResult(emptyList())

    return DefinitionResult(
        locationsFor(reference, resourceTables(reference.packageName)) {
          params.cancelChecker.abortIfCancelled()
        }
    )
  }

  internal fun locationsFor(
      reference: XmlResourceReference,
      tables: Collection<IResourceTable>,
      checkCancelled: () -> Unit = {},
  ): List<Location> {
    val locations = mutableListOf<Location>()
    val seen = mutableSetOf<Pair<Path, Int>>()
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
            .forEach valueLoop@ { configValue ->
              checkCancelled()
              val source = configValue.value?.source ?: return@valueLoop
              val path = sourcePath(source.path) ?: return@valueLoop
              val line = (source.line ?: 1).coerceAtLeast(1) - 1
              if (seen.add(path to line)) {
                val position = Position(line, 0)
                locations.add(Location(path, Range(position, position)))
              }
            }
      }
    }
    return locations
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

  private fun sourcePath(path: String): Path? {
    if (path.isBlank()) return null
    return try {
      Paths.get(path)
    } catch (_: InvalidPathException) {
      null
    }
  }

  internal fun referenceAt(document: DOMDocument, text: String, cursor: Int): String? {
    val attr = document.findAttrAt(cursor)
    attributeReferenceAt(attr, text, cursor)?.let { return it }

    val node = document.findNodeAt(cursor)
    if (node is DOMText) {
      val raw = node.data
      val value = raw.trim()
      if (value.isEmpty()) return null
      val leading = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
      val start = node.startContent + leading
      if (cursor in start..(start + value.length)) {
        return value
      }
    }
    return null
  }

  private fun attributeReferenceAt(attr: DOMAttr?, text: String, cursor: Int): String? {
    val valueNode = attr?.nodeAttrValue ?: return null
    var start = valueNode.start
    var end = valueNode.end
    if (start !in text.indices || end <= start || end > text.length) return null
    if (text[start] == '\'' || text[start] == '"') start++
    if (end > start && (text[end - 1] == '\'' || text[end - 1] == '"')) end--
    if (cursor !in start..end) return null
    return text.substring(start, end)
  }

  private companion object {
    const val ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android"
    val log = LoggerFactory.getLogger(XmlDefinitionProvider::class.java)
  }
}
