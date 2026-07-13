/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package com.tom.rv2ide.lsp.xml.diagnostics

import com.android.aapt.Resources.Attribute.FormatFlags
import com.android.aapt.Resources.Attribute.FormatFlags.BOOLEAN
import com.android.aapt.Resources.Attribute.FormatFlags.COLOR
import com.android.aapt.Resources.Attribute.FormatFlags.DIMENSION
import com.android.aapt.Resources.Attribute.FormatFlags.ENUM
import com.android.aapt.Resources.Attribute.FormatFlags.FLAGS
import com.android.aapt.Resources.Attribute.FormatFlags.STRING
import com.android.aaptcompiler.AaptResourceType.ATTR
import com.android.aaptcompiler.AaptResourceType.LAYOUT
import com.android.aaptcompiler.AaptResourceType.STYLEABLE
import com.android.aaptcompiler.AttributeResource
import com.android.aaptcompiler.ConfigDescription
import com.android.aaptcompiler.Styleable
import com.android.aaptcompiler.extractPathData
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.DiagnosticSeverity.ERROR
import com.tom.rv2ide.lsp.models.DiagnosticSeverity.WARNING
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.xml.res.IResourceGroup
import com.tom.rv2ide.xml.resources.ResourceTableRegistry
import com.tom.rv2ide.xml.widgets.Widget
import com.tom.rv2ide.xml.widgets.WidgetTable
import java.nio.file.Path
import org.eclipse.lemminx.dom.DOMAttr
import org.eclipse.lemminx.dom.DOMElement
import org.eclipse.lemminx.dom.DOMNode
import org.eclipse.lemminx.dom.DOMParser
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

  private val resourceResolver = XmlResourceResolver()

  fun analyze(file: Path): DiagnosticResult {
    val text = runCatching { file.toFile().readText() }.getOrElse { error ->
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
    val isLayoutFile = runCatching { extractPathData(file.toFile()).type == LAYOUT }.getOrDefault(false)
    val collector = XmlDiagnosticCollector(text)
    visit(document, collector, isLayoutFile)

    return DiagnosticResult(file, collector.build(), CHANNEL)
  }

  private fun visit(node: DOMNode, collector: XmlDiagnosticCollector, isLayoutFile: Boolean) {
    if (node is DOMElement) {
      checkDuplicateAttributes(node, collector)
      checkUndeclaredAttributePrefixes(node, collector)
      checkAndroidNamespace(node, collector)
      checkResourceReferences(node, collector)
      if (isLayoutFile) {
        checkLayoutTag(node, collector)
        checkLayoutAttributes(node, collector)
        checkLayoutAttributeValues(node, collector)
      }
    }

    node.children.forEach { child -> visit(child, collector, isLayoutFile) }
  }

  private fun checkDuplicateAttributes(element: DOMElement, collector: XmlDiagnosticCollector) {
    val seen = HashSet<String>()
    element.attributeNodes.orEmpty().forEach { attribute ->
      val name = attribute.name ?: return@forEach
      if (!seen.add(name)) {
        collector.error(
            code = CODE_DUPLICATE_ATTRIBUTE,
            message = "Duplicate attribute '$name'",
            attribute = attribute,
        )
      }
    }
  }

  private fun checkUndeclaredAttributePrefixes(
      element: DOMElement,
      collector: XmlDiagnosticCollector,
  ) {
    element.attributeNodes.orEmpty().forEach { attribute ->
      val name = attribute.name ?: return@forEach
      if (isNamespaceDeclaration(name)) {
        return@forEach
      }
      val separator = name.indexOf(':')
      if (separator <= 0) {
        return@forEach
      }

      val prefix = name.substring(0, separator)
      if (element.getNamespaceURI(prefix) == null) {
        collector.error(
            code = CODE_UNDECLARED_NAMESPACE,
            message = "Namespace prefix '$prefix' is not declared",
            attribute = attribute,
        )
      }
    }
  }

  private fun checkLayoutAttributes(element: DOMElement, collector: XmlDiagnosticCollector) {
    if (!element.isClosed) {
      return
    }
    val tagName = element.tagName ?: return
    val widgetTable = Lookup.getDefault().lookup(WidgetTable.COMPLETION_LOOKUP_KEY) ?: return
    val widget = widgetFor(tagName, widgetTable) ?: return
    val frameworkTable =
        Lookup.getDefault().lookup(ResourceTableRegistry.COMPLETION_FRAMEWORK_RES) ?: return
    val androidPackage = frameworkTable.findPackage(ResourceTableRegistry.PCK_ANDROID) ?: return
    val styleables = androidPackage.findGroup(STYLEABLE) ?: return
    val allowedAttributes = styleablesFor(widget, element, widgetTable, styleables)
        .flatMapTo(mutableSetOf()) { styleable -> styleable.entries.mapNotNull { it.name.entry } }
    if (allowedAttributes.isEmpty()) {
      return
    }

    element.attributeNodes.orEmpty().forEach { attribute ->
      val name = attribute.name ?: return@forEach
      if (!name.startsWith(ANDROID_ATTRIBUTE_PREFIX)) {
        return@forEach
      }
      val localName = name.removePrefix(ANDROID_ATTRIBUTE_PREFIX)
      if (localName !in allowedAttributes) {
        collector.error(
            code = CODE_UNKNOWN_LAYOUT_ATTRIBUTE,
            message = "Unknown attribute '$name' for $tagName",
            attribute = attribute,
        )
      }
    }
  }

  /**
   * Validates only narrow, literal-only formats. References, expressions, and attributes accepting
   * string/integer-like values are intentionally left to AAPT2, because they cannot be rejected
   * reliably from the editor snapshot alone.
   */
  private fun checkLayoutAttributeValues(element: DOMElement, collector: XmlDiagnosticCollector) {
    if (!element.isClosed) {
      return
    }
    val tagName = element.tagName ?: return
    val widgetTable = Lookup.getDefault().lookup(WidgetTable.COMPLETION_LOOKUP_KEY) ?: return
    if (widgetFor(tagName, widgetTable) == null) {
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
    return value.startsWith("@") || value.startsWith("?") || value.startsWith("@{") || value.startsWith("@={")
  }

  private fun AttributeResource.hasAnyType(vararg types: FormatFlags): Boolean {
    return types.any { typeMask and it.number != 0 }
  }

  private fun widgetFor(tagName: String, widgetTable: WidgetTable): Widget? {
    return if (tagName.contains('.')) widgetTable.getWidget(tagName) else widgetTable.findWidgetWithSimpleName(tagName)
  }

  private fun styleablesFor(
      widget: Widget,
      node: DOMElement,
      widgetTable: WidgetTable,
      styleables: IResourceGroup,
      includeLayoutParams: Boolean = true,
      suffix: String = "",
  ): Set<Styleable> {
    val result = mutableSetOf<Styleable>()
    addStyleable(styleables, widget.simpleName, suffix, result)
    widget.superclasses.forEach { superclass ->
      if (superclass == VIEW_GROUP_CLASS) {
        addStyleable(styleables, VIEW_GROUP, MARGIN_LAYOUT_SUFFIX, result)
      }
      widgetTable.getWidget(superclass)?.also { addStyleable(styleables, it.simpleName, suffix, result) }
    }

    if (includeLayoutParams && node.parentNode is DOMElement) {
      val parent = node.parentNode as DOMElement
      widgetFor(parent.tagName ?: "", widgetTable)?.let {
        result.addAll(styleablesFor(it, parent, widgetTable, styleables, false, LAYOUT_SUFFIX))
      } ?: run {
        addStyleable(styleables, VIEW_GROUP, LAYOUT_SUFFIX, result)
        addStyleable(styleables, VIEW_GROUP, MARGIN_LAYOUT_SUFFIX, result)
        addStyleable(styleables, parent.tagName ?: "", LAYOUT_SUFFIX, result)
      }
    }
    return result
  }

  private fun addStyleable(
      styleables: IResourceGroup,
      widgetName: String,
      suffix: String,
      result: MutableSet<Styleable>,
  ) {
    val value = styleables.findEntry("$widgetName$suffix")?.findValue(ConfigDescription())?.value
    if (value is Styleable) {
      result.add(value)
    }
  }

  private fun checkLayoutTag(element: DOMElement, collector: XmlDiagnosticCollector) {
    val tagName = element.tagName ?: return
    if (!element.isClosed || tagName in LAYOUT_SPECIAL_TAGS || tagName.contains('.') || tagName.contains(':')) {
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

  private fun checkResourceReferences(element: DOMElement, collector: XmlDiagnosticCollector) {
    element.attributeNodes.orEmpty().forEach { attribute ->
      val value = attribute.value ?: return@forEach
      if (value.startsWith("@{") || value.startsWith("@={")) {
        return@forEach
      }

      val reference = XmlResourceReference.parse(value) ?: return@forEach
      if (resourceResolver.resolve(reference) == XmlResourceResolver.Resolution.NotFound) {
        collector.errorValue(
            code = CODE_UNRESOLVED_RESOURCE,
            message = "Cannot resolve resource reference '${reference.text}'",
            attribute = attribute,
        )
      }
    }
  }

  private fun checkAndroidNamespace(element: DOMElement, collector: XmlDiagnosticCollector) {
    element.attributeNodes.orEmpty().forEach { attribute ->
      if (attribute.name != ANDROID_NAMESPACE_DECLARATION) {
        return@forEach
      }
      if (attribute.value != ANDROID_NAMESPACE_URI) {
        collector.warning(
            code = CODE_INVALID_ANDROID_NAMESPACE,
            message = "The android namespace must be '$ANDROID_NAMESPACE_URI'",
            attribute = attribute,
        )
      }
    }
  }

  private fun isNamespaceDeclaration(name: String): Boolean {
    return name == XMLNS_ATTRIBUTE || name.startsWith(XMLNS_PREFIX)
  }

  private class XmlDiagnosticCollector(private val text: String) {
    private val diagnostics = mutableListOf<DiagnosticItem>()
    private val keys = HashSet<String>()

    fun error(code: String, message: String, attribute: DOMAttr) {
      add(code, message, ERROR, attribute)
    }

    fun warning(code: String, message: String, attribute: DOMAttr) {
      add(code, message, WARNING, attribute)
    }

    fun errorTag(code: String, message: String, element: DOMElement) {
      val tagName = element.tagName ?: return
      val start = (element.start + 1).coerceIn(0, text.length)
      val end = (start + tagName.length).coerceIn(start, text.length)
      add(code, message, ERROR, start, end)
    }

    fun errorValue(code: String, message: String, attribute: DOMAttr) {
      val valueRange = attribute.nodeAttrValue ?: return
      var start = valueRange.start.coerceIn(0, text.length)
      var end = valueRange.end.coerceIn(start, text.length)
      if (end - start >= 2 && text[start] in QUOTES && text[end - 1] == text[start]) {
        start++
        end--
      }
      add(code, message, ERROR, start, end)
    }

    private fun add(
        code: String,
        message: String,
        severity: com.tom.rv2ide.lsp.models.DiagnosticSeverity,
        attribute: DOMAttr,
    ) {
      val nameRange = attribute.nodeAttrName ?: return
      val start = nameRange.start.coerceIn(0, text.length)
      val end = nameRange.end.coerceIn(start, text.length)
      add(code, message, severity, start, end)
    }

    private fun add(
        code: String,
        message: String,
        severity: com.tom.rv2ide.lsp.models.DiagnosticSeverity,
        start: Int,
        end: Int,
    ) {
      val key = "$code:$start:$end"
      if (!keys.add(key)) {
        return
      }

      diagnostics +=
          DiagnosticItem(
              message = message,
              code = code,
              range = Range(offsetToPosition(start), offsetToPosition(end)),
              source = SOURCE,
              severity = severity,
          )
    }

    fun build(): List<DiagnosticItem> = diagnostics.sortedWith(DiagnosticItem.START_COMPARATOR)

    private fun offsetToPosition(offset: Int): Position {
      var line = 0
      var lineStart = 0
      for (index in 0 until offset) {
        if (text[index] == '\n') {
          line++
          lineStart = index + 1
        }
      }
      return Position(line, offset - lineStart)
    }
  }

  companion object {
    private val log = LoggerFactory.getLogger(XmlDiagnosticsService::class.java)

    const val CHANNEL = "xml-lsp"
    const val SOURCE = "xml-lsp"
    const val CODE_DUPLICATE_ATTRIBUTE = "XML002"
    const val CODE_UNDECLARED_NAMESPACE = "XML003"
    const val CODE_INVALID_ANDROID_NAMESPACE = "XML004"
    const val CODE_UNRESOLVED_RESOURCE = "AXML003"
    const val CODE_UNKNOWN_LAYOUT_TAG = "AXML001"
    const val CODE_UNKNOWN_LAYOUT_ATTRIBUTE = "AXML002"
    const val CODE_INVALID_ATTRIBUTE_VALUE = "AXML004"
    const val ANDROID_ATTRIBUTE_PREFIX = "android:"
    const val VIEW_GROUP_CLASS = "android.view.ViewGroup"
    const val VIEW_GROUP = "ViewGroup"
    const val LAYOUT_SUFFIX = "_Layout"
    const val MARGIN_LAYOUT_SUFFIX = "_MarginLayout"
    val LAYOUT_SPECIAL_TAGS = setOf("include", "merge", "view", "fragment", "tag", "layout")
    const val QUOTES = "\"'"
    const val XMLNS_ATTRIBUTE = "xmlns"
    const val XMLNS_PREFIX = "xmlns:"
    const val ANDROID_NAMESPACE_DECLARATION = "xmlns:android"
    const val ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android"
  }
}
