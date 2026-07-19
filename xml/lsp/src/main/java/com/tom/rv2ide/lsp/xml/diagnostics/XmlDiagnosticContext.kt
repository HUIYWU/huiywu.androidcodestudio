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
import com.android.aaptcompiler.AaptResourceType.LAYOUT
import com.android.aaptcompiler.extractPathData
import java.nio.file.Path
import org.eclipse.lemminx.dom.DOMDocument
import org.eclipse.lemminx.dom.DOMElement
import org.eclipse.lemminx.dom.DOMNode

/** Immutable document-local state shared by all rules in one diagnostic pass. */
internal data class XmlDiagnosticContext(
    val file: Path,
    val text: String,
    val document: DOMDocument,
    val isLayoutFile: Boolean,
    val isValuesFile: Boolean,
    val isManifestFile: Boolean,
    val declaredIds: Set<String>,
    val moduleResourceIds: ModuleResourceIdIndex.Snapshot,
) {
  companion object {
    fun create(file: Path, text: String, document: DOMDocument): XmlDiagnosticContext {
      val pathData = runCatching { extractPathData(file.toFile()) }.getOrNull()
      return XmlDiagnosticContext(
          file = file,
          text = text,
          document = document,
          isLayoutFile = pathData?.type == LAYOUT,
          isValuesFile = pathData?.resourceDirectory == VALUES_DIRECTORY,
          isManifestFile = pathData?.file?.name == ANDROID_MANIFEST_FILE_NAME,
          declaredIds = collectLocalIdDeclarations(document),
          moduleResourceIds = ModuleResourceIdIndex.snapshot(file, text),
      )
    }
  }
}

/** Collects unqualified IDs created in this document before resource tables are refreshed. */
internal fun collectLocalIdDeclarations(document: DOMNode): Set<String> {
  val declaredIds = mutableSetOf<String>()

  fun collect(node: DOMNode) {
    if (node is DOMElement) {
      node.attributeNodes.orEmpty().forEach { attribute ->
        val value = attribute.value ?: return@forEach
        if (!value.startsWith("@+")) {
          return@forEach
        }
        // Reinterpret @+id/name as @id/name to reuse complete-reference validation.
        val reference = XmlResourceReference.parse("@${value.removePrefix("@+")}") ?: return@forEach
        if (reference.packageName == null && !reference.isThemeAttribute && reference.type == ID) {
          declaredIds.add(reference.entry)
        }
      }
    }
    node.children.forEach(::collect)
  }

  collect(document)
  return declaredIds
}

private const val VALUES_DIRECTORY = "values"
private const val ANDROID_MANIFEST_FILE_NAME = "AndroidManifest.xml"
