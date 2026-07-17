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

import com.android.aaptcompiler.AaptResourceType
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.xml.res.IResourceTable
import com.tom.rv2ide.xml.resources.ResourceTableRegistry

/**
 * Resolves resource references against the same resource-table snapshots consumed by XML
 * completion. It deliberately does not initialize or rebuild tables: an unavailable table means
 * the result is unknown, not that a resource is missing.
 */
internal class XmlResourceResolver {

  fun resolve(reference: XmlResourceReference): Resolution {
    val tables = tablesFor(reference.packageName) ?: return Resolution.Unavailable
    return if (tables.any { it.contains(reference) }) Resolution.Resolved else Resolution.NotFound
  }

  private fun tablesFor(packageName: String?): Set<IResourceTable>? {
    if (packageName == ResourceTableRegistry.PCK_ANDROID) {
      return Lookup.getDefault()
          .lookup(ResourceTableRegistry.COMPLETION_FRAMEWORK_RES)
          ?.let(::setOf)
    }

    val moduleTables =
        Lookup.getDefault().lookup(ResourceTableRegistry.COMPLETION_MODULE_RES) ?: emptySet()
    val dependencyTables =
        Lookup.getDefault().lookup(ResourceTableRegistry.COMPLETION_DEP_RES) ?: emptySet()
    val tables = (moduleTables + dependencyTables).toSet()
    if (tables.isEmpty()) {
      return null
    }

    if (packageName == null) {
      return tables
    }

    val matchingTables = tables.filterTo(mutableSetOf()) { it.findPackage(packageName) != null }
    return matchingTables.ifEmpty { null }
  }

  private fun IResourceTable.contains(reference: XmlResourceReference): Boolean {
    return packages.any { resourcePackage ->
      if (reference.packageName != null && resourcePackage.name != reference.packageName) {
        return@any false
      }
      resourcePackage.findGroup(reference.type)?.findEntry(reference.entry) != null
    }
  }

  internal sealed interface Resolution {
    data object Resolved : Resolution

    data object NotFound : Resolution

    /** Resource tables are not ready or the requested package is not represented in the snapshot. */
    data object Unavailable : Resolution
  }
}

/**
 * A complete Android resource reference such as `@string/title`, or a theme attribute reference
 * such as `?attr/colorPrimary`. The caller may additionally recognize local creating ID references
 * (`@+id/...`). Android special values are identified explicitly by [isSpecialValue] and are not
 * resolved against resource tables.
 */
internal data class XmlResourceReference(
    val text: String,
    val packageName: String?,
    val type: AaptResourceType,
    val entry: String,
    val isThemeAttribute: Boolean,
) {
  companion object {
    private val expression =
        Regex("^([@?])(?:(android|[A-Za-z_][A-Za-z0-9_.]*):)?([A-Za-z_][A-Za-z0-9_]*)/([A-Za-z_][A-Za-z0-9_]*)$")
    private val specialValues = setOf("@", "@null", "@empty")

    fun isSpecialValue(value: String): Boolean = value in specialValues

    fun parse(value: String): XmlResourceReference? {
      if (isSpecialValue(value)) {
        return null
      }
      val match = expression.matchEntire(value) ?: return null
      val marker = match.groupValues[1]
      val typeName = match.groupValues[3]
      // Theme references are always attributes: `?attr/name` or `?android:attr/name`.
      if (marker == "?" && typeName != AaptResourceType.ATTR.tagName) {
        return null
      }
      val type = AaptResourceType.values().firstOrNull { it.tagName == typeName } ?: return null
      if (type == AaptResourceType.UNKNOWN) {
        return null
      }
      return XmlResourceReference(
          text = value,
          packageName = match.groupValues[2].ifBlank { null },
          type = type,
          entry = match.groupValues[4],
          isThemeAttribute = marker == "?",
      )
    }
  }
}