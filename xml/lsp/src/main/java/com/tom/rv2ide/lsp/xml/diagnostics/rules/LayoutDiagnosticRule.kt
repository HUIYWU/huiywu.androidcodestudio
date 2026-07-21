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

import com.android.aapt.Resources.Attribute.FormatFlags.BOOLEAN
import com.android.aapt.Resources.Attribute.FormatFlags.COLOR
import com.android.aapt.Resources.Attribute.FormatFlags.DIMENSION
import com.android.aapt.Resources.Attribute.FormatFlags.ENUM
import com.android.aapt.Resources.Attribute.FormatFlags.FLAGS
import com.android.aapt.Resources.Attribute.FormatFlags.INTEGER
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

/** AXML001, AXML002, AXML004 and AXML006: conservative Layout diagnostics. */
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
    checkCustomAttributes(element, context, collector)
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

  private fun checkCustomAttributes(
      element: DOMElement,
      context: XmlDiagnosticContext,
      collector: XmlDiagnosticCollector,
  ) {
    if (!element.isClosed || context.document.documentElement?.tagName == DATA_BINDING_ROOT_TAG) {
      return
    }

    val styleableNames = applicableCustomStyleableNames(element)
    if (styleableNames.isEmpty()) {
      return
    }

    val lookup = Lookup.getDefault()
    val tables =
        (lookup.lookup(ResourceTableRegistry.COMPLETION_MODULE_RES).orEmpty() +
                lookup.lookup(ResourceTableRegistry.COMPLETION_DEP_RES).orEmpty())
            .toSet()
    if (tables.isEmpty()) {
      return
    }

    element.attributeNodes.orEmpty().forEach { attribute ->
      val name = attribute.name ?: return@forEach
      val namespace = attribute.namespaceURI ?: return@forEach
      if (!name.contains(':') ||
          namespace == ANDROID_NAMESPACE_URI ||
          namespace == TOOLS_NAMESPACE_URI ||
          namespace == XMLNS_NAMESPACE_URI) {
        return@forEach
      }

      val packageName = packageForCustomNamespace(namespace) ?: return@forEach
      val packageFilter = packageName.takeUnless { it == AUTO_PACKAGE }
      val snapshot =
          StyleableResolver.attributeSnapshotForStyleables(
              tables,
              styleableNames,
              packageFilter,
          )
      if (!snapshot.hasStyleableMetadata) {
        return@forEach
      }

      val localName = name.substringAfter(':')
      if (isUnknownCustomAttribute(localName, snapshot.names) { attrName ->
        tables
            .asSequence()
            .flatMap { it.packages.asSequence() }
            .filter { packageFilter == null || it.name == packageFilter }
            .any { it.findGroup(ATTR)?.findEntry(attrName) != null }
      }) {
        collector.error(
            code = CODE_UNKNOWN_CUSTOM_ATTRIBUTE,
            message = "Unknown custom attribute '$name' for ${element.tagName}",
            attribute = attribute,
        )
      }
    }
  }

  internal fun isUnknownCustomAttribute(
      localName: String,
      styleableAttributes: Set<String>,
      existsGlobally: (String) -> Boolean,
  ): Boolean {
    if (localName in styleableAttributes || existsGlobally(localName)) {
      return false
    }
    return styleableAttributes.any { knownName -> isSingleEditAway(localName, knownName) }
  }

  private fun isSingleEditAway(candidate: String, knownName: String): Boolean {
    if (candidate == knownName || kotlin.math.abs(candidate.length - knownName.length) > 1) {
      return false
    }
    if (candidate.length == knownName.length) {
      val differences = candidate.indices.filter { candidate[it] != knownName[it] }
      return differences.size == 1 ||
          (differences.size == 2 &&
              differences[1] == differences[0] + 1 &&
              candidate[differences[0]] == knownName[differences[1]] &&
              candidate[differences[1]] == knownName[differences[0]])
    }

    val longer = if (candidate.length > knownName.length) candidate else knownName
    val shorter = if (candidate.length > knownName.length) knownName else candidate
    var longIndex = 0
    var shortIndex = 0
    var skipped = false
    while (longIndex < longer.length && shortIndex < shorter.length) {
      if (longer[longIndex] == shorter[shortIndex]) {
        longIndex++
        shortIndex++
      } else if (!skipped) {
        skipped = true
        longIndex++
      } else {
        return false
      }
    }
    return true
  }

  private fun applicableCustomStyleableNames(element: DOMElement): Set<String> {
    val names = mutableSetOf<String>()
    val tagName = element.tagName.orEmpty()
    if (tagName.contains('.') && !tagName.startsWith(ANDROID_VIEW_PACKAGE_PREFIX)) {
      names.add(StyleableResolver.simpleName(tagName))
    }

    val parentTag = (element.parentNode as? DOMElement)?.tagName.orEmpty()
    if (parentTag.contains('.') && !parentTag.startsWith(ANDROID_VIEW_PACKAGE_PREFIX)) {
      names.add("${StyleableResolver.simpleName(parentTag)}${StyleableResolver.LAYOUT_SUFFIX}")
    }
    return names
  }

  private fun packageForCustomNamespace(namespace: String): String? {
    if (namespace == AUTO_NAMESPACE_URI) {
      return AUTO_PACKAGE
    }
    if (!namespace.startsWith(RESOURCE_NAMESPACE_PREFIX)) {
      return null
    }
    return namespace.removePrefix(RESOURCE_NAMESPACE_PREFIX).takeIf { it.isNotBlank() }
  }

  /** Checks only narrow, single-format literal values with unambiguous Android syntax. */
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
    val symbols = attr.symbols.mapNotNull { it.symbol.name.entry }.toSet()
    return validateLiteralAttributeValue(attr.typeMask, symbols, value)
  }

  /** Validates only pure, unambiguous inline formats; mixed masks remain intentionally permissive. */
  internal fun validateLiteralAttributeValue(
      typeMask: Int,
      symbols: Set<String>,
      value: String,
  ): String? {
    return when (typeMask) {
      BOOLEAN.number ->
          if (value == "true" || value == "false") null
          else "Expected a boolean value ('true' or 'false')"
      INTEGER.number ->
          if (INTEGER_LITERAL.matches(value)) null else "Expected an integer value"
      DIMENSION.number ->
          if (value in ZERO_DIMENSION_LITERALS || DIMENSION_LITERAL.matches(value)) null
          else "Expected a dimension value with a valid unit"
      COLOR.number ->
          if (COLOR_LITERAL.matches(value)) null else "Expected a color value"
      ENUM.number ->
          if (value in symbols) null else "'$value' is not a valid value for this enum attribute"
      FLAGS.number -> {
        val flags = value.split('|').map(String::trim)
        if (flags.isNotEmpty() && flags.all { it.isNotEmpty() && it in symbols }) null
        else "'$value' contains an invalid flag value"
      }
      else -> null
    }
  }

  private fun isDeferredAttributeValue(value: String): Boolean {
    return value.startsWith("@") ||
        value.startsWith("?") ||
        value.startsWith("@{") ||
        value.startsWith("@={")
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
  private const val CODE_UNKNOWN_CUSTOM_ATTRIBUTE = "AXML006"
  private const val ANDROID_ATTRIBUTE_PREFIX = "android:"
  private const val LAYOUT_ATTRIBUTE_PREFIX = "layout_"
  private const val ANDROID_VIEW_PACKAGE_PREFIX = "android."
  private const val DATA_BINDING_ROOT_TAG = "layout"
  private const val AUTO_NAMESPACE_URI = "http://schemas.android.com/apk/res-auto"
  private const val RESOURCE_NAMESPACE_PREFIX = "http://schemas.android.com/apk/res/"
  private const val ANDROID_NAMESPACE_URI =
      "http://schemas.android.com/apk/res/android"
  private const val TOOLS_NAMESPACE_URI = "http://schemas.android.com/tools"
  private const val XMLNS_NAMESPACE_URI = "http://www.w3.org/2000/xmlns/"
  private const val AUTO_PACKAGE = "<auto>"
  private val INTEGER_LITERAL = Regex("^[+-]?(?:0[xX][0-9a-fA-F]+|[0-9]+)$")
  private val ZERO_DIMENSION_LITERALS = setOf("0", "+0", "-0")
  private val DIMENSION_LITERAL =
      Regex("^[+-]?(?:(?:[0-9]+(?:\\.[0-9]*)?)|(?:\\.[0-9]+))(?:[eE][+-]?[0-9]+)?(?:px|dp|dip|sp|pt|in|mm)$")
  private val COLOR_LITERAL = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
  private val LAYOUT_SPECIAL_TAGS =
      setOf("include", "merge", "view", "fragment", "tag", "layout")
}
