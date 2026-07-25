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
package com.tom.rv2ide.lsp.xml.resolver

import com.android.aaptcompiler.AaptResourceType.STYLEABLE
import com.android.aaptcompiler.ConfigDescription
import com.android.aaptcompiler.Styleable
import com.tom.rv2ide.xml.res.IResourceGroup
import com.tom.rv2ide.xml.res.IResourceTable
import com.tom.rv2ide.xml.widgets.Widget
import com.tom.rv2ide.xml.widgets.WidgetTable
import org.eclipse.lemminx.dom.DOMElement
import org.eclipse.lemminx.dom.DOMNode

/** Shared, side-effect-free styleable lookup used by XML completion and diagnostics. */
internal object StyleableResolver {

  data class AttributeSnapshot(
      val names: Set<String>,
      val hasStyleableMetadata: Boolean,
  )

  const val LAYOUT_SUFFIX = "_Layout"
  const val MARGIN_LAYOUT_SUFFIX = "_MarginLayout"
  const val VIEW_GROUP = "ViewGroup"
  const val VIEW_GROUP_CLASS = "android.view.ViewGroup"

  fun widgetFor(tagName: String?, widgets: WidgetTable): Widget? {
    if (tagName.isNullOrBlank()) {
      return null
    }
    return if (tagName.contains('.')) widgets.getWidget(tagName)
    else widgets.findWidgetWithSimpleName(tagName)
  }

  fun findEntry(styleables: IResourceGroup, name: String): Styleable? {
    return styleables.findEntry(name)?.findValue(ConfigDescription())?.value as? Styleable
  }

  fun addEntry(
      styleables: IResourceGroup,
      name: String,
      result: MutableSet<Styleable>,
  ) {
    findEntry(styleables, name)?.let(result::add)
  }

  fun forName(
      styleables: IResourceGroup,
      node: DOMNode,
      includeView: Boolean = true,
      includeParentLayoutParams: Boolean = false,
      suffix: String = "",
  ): Set<Styleable> {
    val result = mutableSetOf<Styleable>()
    if (includeView) {
      addEntry(styleables, "View", result)
    }
    node.nodeName?.takeIf { it.isNotBlank() }?.let { name ->
      addEntry(styleables, "${simpleName(name)}$suffix", result)
    }
    if (includeParentLayoutParams) {
      node.parentNode?.let { result.addAll(layoutParamsFor(styleables, it)) }
    }
    return result
  }

  fun layoutParamsFor(styleables: IResourceGroup, parentNode: DOMNode): Set<Styleable> {
    val result = mutableSetOf<Styleable>()
    addEntry(styleables, "$VIEW_GROUP$LAYOUT_SUFFIX", result)
    addEntry(styleables, "$VIEW_GROUP$MARGIN_LAYOUT_SUFFIX", result)
    parentNode.nodeName?.takeIf { it.isNotBlank() }?.let { name ->
      addEntry(styleables, "${simpleName(name)}$LAYOUT_SUFFIX", result)
    }
    return result
  }

  fun forWidget(
      styleables: IResourceGroup,
      widgets: WidgetTable,
      widget: Widget,
      node: DOMNode,
      includeParentLayoutParams: Boolean = true,
      suffix: String = "",
      includeViewGroupOwnAttributes: Boolean = false,
      includeRootLayoutParams: Boolean = true,
  ): Set<Styleable> {
    val result = mutableSetOf<Styleable>()
    addEntry(styleables, "${widget.simpleName}$suffix", result)
    widget.superclasses.forEach { superclass ->
      if (superclass == VIEW_GROUP_CLASS) {
        if (includeViewGroupOwnAttributes) {
          addEntry(styleables, "$VIEW_GROUP$suffix", result)
        }
        addEntry(styleables, "$VIEW_GROUP$MARGIN_LAYOUT_SUFFIX", result)
      }
      widgets.getWidget(superclass)?.let { addEntry(styleables, "${it.simpleName}$suffix", result) }
    }

    if (includeParentLayoutParams) {
      val parent = node.parentNode as? DOMElement
      if (parent != null) {
        val parentWidget = widgetFor(parent.nodeName, widgets)
        if (parentWidget != null) {
          result.addAll(
              forWidget(
                  styleables,
                  widgets,
                  parentWidget,
                  parent,
                  includeParentLayoutParams = false,
                  suffix = LAYOUT_SUFFIX,
                  includeViewGroupOwnAttributes = includeViewGroupOwnAttributes,
                  includeRootLayoutParams = includeRootLayoutParams,
              )
          )
        } else {
          result.addAll(layoutParamsFor(styleables, parent))
        }
      } else if (includeRootLayoutParams) {
        addEntry(styleables, "$VIEW_GROUP$LAYOUT_SUFFIX", result)
        addEntry(styleables, "$VIEW_GROUP$MARGIN_LAYOUT_SUFFIX", result)
      }
    }
    return result
  }

  fun attributesForStyleable(
      tables: Collection<IResourceTable>,
      styleableName: String,
  ): Set<String> = attributeSnapshotForStyleables(tables, setOf(styleableName)).names

  fun attributeSnapshotForStyleables(
      tables: Collection<IResourceTable>,
      styleableNames: Set<String>,
      packageName: String? = null,
  ): AttributeSnapshot {
    val names = mutableSetOf<String>()
    var hasStyleableMetadata = false
    tables
        .asSequence()
        .flatMap { it.packages.asSequence() }
        .filter { packageName == null || it.name == packageName }
        .forEach packageLoop@ { resourcePackage ->
          val styleables = resourcePackage.findGroup(STYLEABLE) ?: return@packageLoop
          styleableNames.forEach styleableLoop@ { styleableName ->
            val styleable = findEntry(styleables, styleableName) ?: return@styleableLoop
            hasStyleableMetadata = true
            styleable.entries.mapNotNullTo(names) { it.name.entry }
          }
        }
    return AttributeSnapshot(names, hasStyleableMetadata)
  }

  fun simpleName(name: String): String = name.substringAfterLast('.')
}
