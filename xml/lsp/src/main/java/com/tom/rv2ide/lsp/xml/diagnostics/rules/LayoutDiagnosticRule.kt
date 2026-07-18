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

import com.android.aapt.Resources.Attribute.FormatFlags
import com.android.aapt.Resources.Attribute.FormatFlags.BOOLEAN
import com.android.aapt.Resources.Attribute.FormatFlags.COLOR
import com.android.aapt.Resources.Attribute.FormatFlags.DIMENSION
import com.android.aapt.Resources.Attribute.FormatFlags.ENUM
import com.android.aapt.Resources.Attribute.FormatFlags.FLAGS
import com.android.aapt.Resources.Attribute.FormatFlags.STRING
import com.android.aaptcompiler.AaptResourceType.ATTR
import com.android.aaptcompiler.AaptResourceType.STYLEABLE
import com.android.aaptcompiler.AttributeResource
import com.android.aaptcompiler.ConfigDescription
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticCollector
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticContext
import com.tom.rv2ide.lsp.xml.diagnostics.XmlElementDiagnosticRule
import com.tom.rv2ide.lsp.xml.resolver.StyleableResolver
import com.tom.rv2ide.xml.resources.ResourceTableRegistry
import com.tom.rv2ide.xml.widgets.WidgetTable
import org.eclipse.lemminx.dom.DOMElement

/** AXML001, AXML002 and AXML004: conservative framework Layout diagnostics. */
internal object LayoutDiagnosticRule : XmlElementDiagnosticRule {
  override val id: String = "layout"

  override fun supports(context: XmlDiagnosticContext): Boolean = context.isLayoutFile

  override fun diagnose(
      element: DOMElement,
      context: XmlDiagnosticContext,
      collector: XmlDiagnosticCollector,
  ) {
    checkTag(element, collector)
    checkAttributes(element, collector)
    checkAttributeValues(element, collector)
  }

  private fun checkTag(element: DOMElement, collector: XmlDiagnosticCollector) {
    val tagName = element.tagName ?: return
    if (!element.isClosed ||
        tagName in LAYOUT_SPECIAL_TAGS ||
        tagName.contains('.') ||
        tagName.contains(':')) {
      return
    }

    val widgetTable = Lookup.getDefault().lookup(WidgetTable.COMPLETION_LOOKUP_KEY) ?: return
    if (widgetTable.findWidgetWithSimpleName(tagName) == null) {
      collector.errorTag(
          code = CODE_UNKNOWN_LAYOUT_TAG,
          message = "Unknown layout tag '$tagName'",
          element = element,
      )
    }
  }

  private fun checkAttributes(element: DOMElement, collector: XmlDiagnosticCollector) {
    if (!element.isClosed) {
      return
    }
    val tagName = element.tagName ?: return
    val widgetTable = Lookup.getDefault().lookup(WidgetTable.COMPLETION_LOOKUP_KEY) ?: return
    val widget = StyleableResolver.widgetFor(tagName, widgetTable) ?: return
    val frameworkTable =
        Lookup.getDefault().lookup(ResourceTableRegistry.COMPLETION_FRAMEWORK_RES) ?: return
    val androidPackage = frameworkTable.findPackage(ResourceTableRegistry.PCK_ANDROID) ?: return
    val styleables = androidPackage.findGroup(STYLEABLE) ?: return
    val frameworkAttributes = androidPackage.findGroup(ATTR) ?: return
    val dependencyParentLayoutAttributes = dependencyParentLayoutAttributes(element)
    val allowedAttributes =
        StyleableResolver.forWidget(
                styleables,
                widgetTable,
                widget,
                element,
                includeViewGroupOwnAttributes = true,
            )
            .flatMapTo(mutableSetOf()) { styleable ->
              styleable.entries.mapNotNull { it.name.entry }
            }
            .apply { addAll(dependencyParentLayoutAttributes) }
    if (allowedAttributes.isEmpty()) {
      return
    }

    element.attributeNodes.orEmpty().forEach { attribute ->
      val name = attribute.name ?: return@forEach
      if (!name.startsWith(ANDROID_ATTRIBUTE_PREFIX)) {
        return@forEach
      }
      val localName = name.removePrefix(ANDROID_ATTRIBUTE_PREFIX)
      // LayoutParams for non-framework parents are dependency metadata. If that metadata is absent,
      // an android:layout_* name remains unknown rather than invalid.
      if (localName.startsWith(LAYOUT_ATTRIBUTE_PREFIX) &&
          hasNonFrameworkParent(element) &&
          dependencyParentLayoutAttributes.isEmpty()) {
        return@forEach
      }
      // AAPT can accept a platform attribute even when it is absent from this widget's styleable.
      // Attribute applicability belongs to Lint; this rule reports only names absent globally too.
      if (
          isUnknownFrameworkAttribute(localName, allowedAttributes) {
            frameworkAttributes.findEntry(it) != null
          }
      ) {
        collector.error(
            code = CODE_UNKNOWN_LAYOUT_ATTRIBUTE,
            message = "Unknown attribute '$name' for $tagName",
            attribute = attribute,
        )
      }
    }
  }

  internal fun isUnknownFrameworkAttribute(
      localName: String,
      styleableAttributes: Set<String>,
      existsInFramework: (String) -> Boolean,
  ): Boolean {
    return localName !in styleableAttributes && !existsInFramework(localName)
  }

  /** Checks only narrow literal boolean, enum and flag formats. */
  private fun checkAttributeValues(element: DOMElement, collector: XmlDiagnosticCollector) {
    if (!element.isClosed) {
      return
    }
    val tagName = element.tagName ?: return
    val widgetTable = Lookup.getDefault().lookup(WidgetTable.COMPLETION_LOOKUP_KEY) ?: return
    if (StyleableResolver.widgetFor(tagName, widgetTable) == null) {
      return
    }
    val frameworkTable =
        Lookup.getDefault().lookup(ResourceTableRegistry.COMPLETION_FRAMEWORK_RES) ?: return
    val attrs =
        frameworkTable.findPackage(ResourceTableRegistry.PCK_ANDROID)?.findGroup(ATTR) ?: return

    element.attributeNodes.orEmpty().forEach { attribute ->
      val name = attribute.name ?: return@forEach
      if (!name.startsWith(ANDROID_ATTRIBUTE_PREFIX)) {
        return@forEach
      }
      val value = attribute.value ?: return@forEach
      if (isDeferredAttributeValue(value)) {
        return@forEach
      }
      val attr =
          attrs.findEntry(name.removePrefix(ANDROID_ATTRIBUTE_PREFIX))
              ?.findValue(ConfigDescription())
              ?.value as? AttributeResource
              ?: return@forEach
      validateLiteralAttributeValue(attr, value)?.let { message ->
        collector.errorValue(CODE_INVALID_ATTRIBUTE_VALUE, message, attribute)
      }
    }
  }

  // Resource references are not rejected from AttributeResource.typeMask: it describes accepted
  // inline formats and does not imply that references such as android:text="@string/title" fail.
  private fun validateLiteralAttributeValue(attr: AttributeResource, value: String): String? {
    if (attr.hasAnyType(STRING, COLOR, DIMENSION)) {
      return null
    }

    val symbols = attr.symbols.mapNotNull { it.symbol.name.entry }.toSet()
    if (attr.typeMask == BOOLEAN.number && value != "true" && value != "false") {
      return "Expected a boolean value ('true' or 'false')"
    }
    if (attr.typeMask == ENUM.number && value !in symbols) {
      return "'$value' is not a valid value for this enum attribute"
    }
    if (attr.typeMask == FLAGS.number) {
      val flags = value.split('|').map(String::trim)
      if (flags.isEmpty() || flags.any { it.isEmpty() || it !in symbols }) {
        return "'$value' contains an invalid flag value"
      }
    }
    return null
  }

  private fun isDeferredAttributeValue(value: String): Boolean {
    return value.startsWith("@") ||
        value.startsWith("?") ||
        value.startsWith("@{") ||
        value.startsWith("@={")
  }

  private fun AttributeResource.hasAnyType(vararg types: FormatFlags): Boolean {
    return types.any { typeMask and it.number != 0 }
  }

  private fun hasNonFrameworkParent(element: DOMElement): Boolean {
    val parentTag = (element.parentNode as? DOMElement)?.tagName ?: return false
    return parentTag.contains('.') && !parentTag.startsWith(ANDROID_VIEW_PACKAGE_PREFIX)
  }

  /** Resolves a dependency parent's `SimpleName_Layout` styleable when the snapshot provides it. */
  private fun dependencyParentLayoutAttributes(element: DOMElement): Set<String> {
    val parentTag = (element.parentNode as? DOMElement)?.tagName ?: return emptySet()
    if (!parentTag.contains('.') || parentTag.startsWith(ANDROID_VIEW_PACKAGE_PREFIX)) {
      return emptySet()
    }
    val styleableName =
        "${StyleableResolver.simpleName(parentTag)}${StyleableResolver.LAYOUT_SUFFIX}"
    val lookup = Lookup.getDefault()
    val tables =
        (lookup.lookup(ResourceTableRegistry.COMPLETION_MODULE_RES).orEmpty() +
                lookup.lookup(ResourceTableRegistry.COMPLETION_DEP_RES).orEmpty())
            .toSet()
    return StyleableResolver.attributesForStyleable(tables, styleableName)
  }

  private const val CODE_UNKNOWN_LAYOUT_TAG = "AXML001"
  private const val CODE_UNKNOWN_LAYOUT_ATTRIBUTE = "AXML002"
  private const val CODE_INVALID_ATTRIBUTE_VALUE = "AXML004"
  private const val ANDROID_ATTRIBUTE_PREFIX = "android:"
  private const val LAYOUT_ATTRIBUTE_PREFIX = "layout_"
  private const val ANDROID_VIEW_PACKAGE_PREFIX = "android."
  private val LAYOUT_SPECIAL_TAGS =
      setOf("include", "merge", "view", "fragment", "tag", "layout")
}
