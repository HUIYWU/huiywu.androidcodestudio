/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
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

/** A complete, non-creating Android resource reference such as `@string/title`. */
internal data class XmlResourceReference(
    val text: String,
    val packageName: String?,
    val type: AaptResourceType,
    val entry: String,
) {
  companion object {
    private val expression =
        Regex("^@(?:(android|[A-Za-z_][A-Za-z0-9_.]*):)?([A-Za-z_][A-Za-z0-9_]*)/([A-Za-z_][A-Za-z0-9_]*)$")

    fun parse(value: String): XmlResourceReference? {
      val match = expression.matchEntire(value) ?: return null
      val type = AaptResourceType.values().firstOrNull { it.tagName == match.groupValues[2] } ?: return null
      if (type == AaptResourceType.UNKNOWN) {
        return null
      }
      return XmlResourceReference(
          text = value,
          packageName = match.groupValues[1].ifBlank { null },
          type = type,
          entry = match.groupValues[3],
      )
    }
  }
}