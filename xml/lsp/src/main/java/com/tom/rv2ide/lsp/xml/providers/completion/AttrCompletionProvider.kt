/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.lsp.xml.providers.completion

import com.android.aaptcompiler.AaptResourceType.STYLEABLE
import com.android.aaptcompiler.ResourcePathData
import com.android.aaptcompiler.Styleable
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.lsp.api.ICompletionProvider
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.MatchLevel.NO_MATCH
import com.tom.rv2ide.lsp.xml.resolver.StyleableResolver
import com.tom.rv2ide.lsp.xml.utils.XmlUtils.NodeType
import com.tom.rv2ide.lsp.xml.utils.XmlUtils.NodeType.ATTRIBUTE
import com.tom.rv2ide.xml.res.IResourceGroup
import com.tom.rv2ide.xml.res.IResourceTablePackage
import com.tom.rv2ide.xml.widgets.Widget
import com.tom.rv2ide.xml.widgets.WidgetTable
import org.eclipse.lemminx.dom.DOMDocument
import org.eclipse.lemminx.dom.DOMNode

/**
 * Provides attribute completions in layout files.
 *
 * @author Akash Yadav
 */
open class AttrCompletionProvider(provider: ICompletionProvider) :
    IXmlCompletionProvider(provider) {

  private var attrHasNamespace = false

  override fun canProvideCompletions(pathData: ResourcePathData, type: NodeType): Boolean {
    return super.canProvideCompletions(pathData, type) && type == ATTRIBUTE
  }

  override fun doComplete(
      params: CompletionParams,
      pathData: ResourcePathData,
      document: DOMDocument,
      type: NodeType,
      prefix: String,
  ): CompletionResult {
    val list = mutableListOf<CompletionItem>()

    val newPrefix =
        if (attrAtCursor.name.contains(':')) {
          attrAtCursor.name.substringAfterLast(':')
        } else attrAtCursor.name

    attrHasNamespace = newPrefix != attrAtCursor.name

    val namespace =
        attrAtCursor.namespaceURI
            ?: run {
              return completeFromAllNamespaces(nodeAtCursor, list, newPrefix)
            }

    val nsPrefix = attrAtCursor.nodeName.substringBefore(':')
    completeForNamespace(namespace, nsPrefix, nodeAtCursor, newPrefix, list)

    return CompletionResult(list)
  }

  private fun completeFromAllNamespaces(
      node: DOMNode,
      list: MutableList<CompletionItem>,
      newPrefix: String,
  ): CompletionResult {
    val namespaces = findAllNamespaces(node)
    namespaces.forEach { completeForNamespace(it.second, it.first, node, newPrefix, list) }

    return CompletionResult(list)
  }

  protected open fun completeForNamespace(
      namespace: String?,
      nsPrefix: String,
      node: DOMNode,
      newPrefix: String,
      list: MutableList<CompletionItem>,
  ) {
    if (namespace == null) {
      log.warn("Namespace is null. Cannot compute completions for namespace prefix: {}.", nsPrefix)
      return
    }
    val tables = findResourceTables(namespace)
    if (tables.isEmpty()) {
      log.warn("No resource tables found for namespace: {}", namespace)
      return
    }

    val pck = namespace.substringAfter(NAMESPACE_PREFIX)
    val packages = mutableSetOf<IResourceTablePackage>()
    for (table in tables) {
      if (namespace == NAMESPACE_AUTO) {
        packages.addAll(table.packages.filter { it.name.isNotBlank() })
      } else {
        val tablePackage = table.findPackage(pck)
        tablePackage?.also { packages.add(it) }
      }
    }

    for (tablePackage in packages) {
      addFromPackage(tablePackage, node, tablePackage.name, nsPrefix, newPrefix, list)
    }
  }

  protected open fun addFromPackage(
      tablePackage: IResourceTablePackage?,
      node: DOMNode,
      pck: String,
      nsPrefix: String,
      newPrefix: String,
      list: MutableList<CompletionItem>,
  ) {
    val styleables = tablePackage?.findGroup(STYLEABLE) ?: return
    val nodeStyleables = findNodeStyleables(node, styleables)
    if (nodeStyleables.isEmpty()) {
      return
    }

    addFromStyleables(
        styleables = nodeStyleables,
        pck = pck,
        pckPrefix = nsPrefix,
        prefix = newPrefix,
        list = list,
    )
  }

  protected open fun addFromStyleables(
      styleables: Set<Styleable>,
      pck: String,
      pckPrefix: String,
      prefix: String,
      list: MutableList<CompletionItem>,
  ) {
    for (nodeStyleable in styleables) {
      for (ref in nodeStyleable.entries) {
        val matchLevel = matchLevel(ref.name.entry!!, prefix)
        if (matchLevel == NO_MATCH || hasAttr(pckPrefix, ref)) {
          continue
        }
        list.add(
            createAttrCompletionItem(
                attr = ref,
                resPkg = pck,
                nsPrefix = pckPrefix,
                hasNamespace = attrHasNamespace,
                matchLevel = matchLevel,
                partial = prefix,
            )
        )
      }
    }
  }

  protected open fun hasAttr(prefix: String, ref: com.android.aaptcompiler.Reference): Boolean {
    return this.nodeAtCursor.hasAttribute("${prefix}:${ref.name.entry}")
  }

  protected open fun findNodeStyleables(node: DOMNode, styleables: IResourceGroup): Set<Styleable> {
    val nodeName = node.nodeName
    val widgets = Lookup.getDefault().lookup(WidgetTable.COMPLETION_LOOKUP_KEY) ?: return emptySet()

    // Find the widget
    val widget = StyleableResolver.widgetFor(nodeName, widgets)

    if (widget != null) {
      // This is a widget from the Android SDK
      // we can get its superclasses and other stuff
      return findStyleablesForWidget(styleables, widgets, widget, node)
    } else if (nodeName.contains('.')) {
      // Probably a custom view or a view from libraries
      // If the developer follows the naming convention then only the completions will be provided
      // This must be called if and only if the tag name is qualified
      return findStyleablesForName(styleables, node, true)
    }

    log.info("Cannot find styleable entries for tag: null")
    return emptySet()
  }

  protected open fun findStyleablesForName(
      styleables: IResourceGroup,
      node: DOMNode,
      addFromParent: Boolean = false,
      suffix: String = "",
  ): Set<Styleable> {
    return StyleableResolver.forName(
        styleables,
        node,
        includeView = true,
        includeParentLayoutParams = addFromParent,
        suffix = suffix,
    )
  }

  protected open fun findLayoutParams(
      styleables: IResourceGroup,
      parentNode: DOMNode,
  ): Set<Styleable> {
    return StyleableResolver.layoutParamsFor(styleables, parentNode)
  }

  protected open fun findStyleablesForWidget(
      styleables: IResourceGroup,
      widgets: WidgetTable,
      widget: Widget,
      node: DOMNode,
      adddFromParent: Boolean = true,
      suffix: String = "",
  ): Set<Styleable> {
    return StyleableResolver.forWidget(
        styleables,
        widgets,
        widget,
        node,
        includeParentLayoutParams = adddFromParent,
        suffix = suffix,
        includeViewGroupOwnAttributes = false,
        includeRootLayoutParams = true,
    )
  }

  protected open fun addWidgetStyleable(
      styleables: IResourceGroup,
      widget: Widget,
      result: MutableSet<Styleable>,
      suffix: String = "",
  ) {
    addWidgetStyleable(styleables, widget.simpleName, result, suffix)
  }

  protected open fun addWidgetStyleable(
      styleables: IResourceGroup,
      widget: String,
      result: MutableSet<Styleable>,
      suffix: String = "",
  ) {
    val entry = findStyleableEntry(styleables, "${widget}${suffix}")
    if (entry != null) {
      result.add(entry)
    }
  }

  protected open fun addSuperclassStyleables(
      styleables: IResourceGroup,
      widgets: WidgetTable,
      widget: Widget,
      result: MutableSet<Styleable>,
      suffix: String = "",
  ) {
    for (superclass in widget.superclasses) {
      // When a ViewGroup is encountered in the superclasses, add the margin layout params
      if ("android.view.ViewGroup" == superclass) {
        addWidgetStyleable(styleables, "ViewGroup", result, suffix = "_MarginLayout")
      }

      val superr = widgets.getWidget(superclass) ?: continue
      addWidgetStyleable(styleables, superr.simpleName, result, suffix = suffix)
    }
  }

  protected open fun findStyleableEntry(styleables: IResourceGroup, name: String): Styleable? {
    return StyleableResolver.findEntry(styleables, name).also { value ->
      if (value == null) {
        log.warn("Cannot find styleable for {}", name)
      }
    }
  }
}
