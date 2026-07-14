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
import com.android.aapt.Resources.Attribute.FormatFlags.REFERENCE
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
import com.tom.rv2ide.lsp.util.setupLookupForCompletion
import com.tom.rv2ide.lsp.models.DiagnosticSeverity.ERROR
import com.tom.rv2ide.lsp.models.DiagnosticSeverity.WARNING
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.FileManager
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
    // Diagnostics use the same resource and widget snapshots as completion. Without this setup,
    // Android-specific rules only become eligible after another feature or tooling warm-up happens
    // to populate Lookup, making diagnostics appear to depend on a build.
    runCatching { setupLookupForCompletion(file) }
        .onFailure { error -> log.debug("Unable to prepare XML diagnostic lookup for {}", file, error) }

    // Active editor documents may contain unsaved changes. FileManager returns that in-memory
    // snapshot when present and falls back to disk for inactive files.
    val text = runCatching { FileManager.getDocumentContents(file) }.getOrElse { error ->
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
    val pathData = runCatching { extractPathData(file.toFile()) }.getOrNull()
    val isLayoutFile = pathData?.type == LAYOUT
    val isValuesFile = pathData?.resourceDirectory == VALUES_DIRECTORY
    val isManifestFile = pathData?.file?.name == ANDROID_MANIFEST_FILE_NAME
    val collector = XmlDiagnosticCollector(text)
    if (isValuesFile) {
      checkValuesDocument(document.documentElement, collector)
    }
    if (isManifestFile) {
      checkManifestDocument(document.documentElement, collector)
    }
    visit(document, collector, isLayoutFile, isManifestFile)

    return DiagnosticResult(file, collector.build(), CHANNEL)
  }

  private fun visit(
      node: DOMNode,
      collector: XmlDiagnosticCollector,
      isLayoutFile: Boolean,
      isManifestFile: Boolean,
  ) {
    if (node is DOMElement) {
      val hasSyntaxRecovery = checkSyntaxRecovery(node, collector)
      if (!hasSyntaxRecovery) {
        checkDuplicateAttributes(node, collector)
        checkUndeclaredAttributePrefixes(node, collector)
        checkAndroidNamespace(node, collector)
        checkResourceReferences(node, collector)
        if (isLayoutFile) {
          checkLayoutTag(node, collector)
          checkLayoutAttributes(node, collector)
          checkLayoutAttributeValues(node, collector)
          checkLayoutReferenceCompatibility(node, collector)
        }
        if (isManifestFile) {
          checkManifestParent(node, collector)
          checkManifestAttributes(node, collector)
          checkManifestComponentName(node, collector)
        }
      }
    }

    node.children.forEach { child -> visit(child, collector, isLayoutFile, isManifestFile) }
  }

  /**
   * Reports only recovery states explicitly represented by LemMinX's tolerant DOM. This is not a
   * substitute for a full parser-error stream, but it gives stable ranges for common structural
   * failures without attempting to re-parse XML using regular expressions.
   */
  private fun checkSyntaxRecovery(element: DOMElement, collector: XmlDiagnosticCollector): Boolean {
    val tagName = element.tagName
    if (element.isOrphanEndTag) {
      collector.errorRange(
          code = CODE_XML_SYNTAX,
          message = if (tagName == null) "Unexpected closing tag" else "Unexpected closing tag '</$tagName>'",
          start = element.start,
          end = element.end,
      )
      return true
    }
    if (!element.hasStartTag() || tagName == null) {
      return false
    }
    // LemMinX records a `/>` token as self-closed but may leave startTagCloseOffset unset.
    // A self-closed element is nevertheless syntactically complete and must not be diagnosed as
    // an unclosed start tag.
    if (element.isSelfClosed) {
      return false
    }
    if (!element.isStartTagClosed) {
      collector.errorRange(
          code = CODE_XML_SYNTAX,
          message = "Start tag '<$tagName>' is not closed",
          start = element.start,
          end = element.unclosedStartTagCloseOffset,
      )
      return true
    }
    if (!element.isSelfClosed && !element.isClosed && !hasDirectSyntaxRecoveryChild(element)) {
      val nameStart = element.start + 1
      collector.errorRange(
          code = CODE_XML_SYNTAX,
          message = "Element '<$tagName>' is missing an end tag",
          start = nameStart,
          end = nameStart + tagName.length,
      )
      return true
    }
    return false
  }

  private fun hasDirectSyntaxRecoveryChild(element: DOMElement): Boolean {
    return element.children.any { child ->
      child is DOMElement &&
          (child.isOrphanEndTag ||
              (child.hasStartTag() &&
                  !child.isSelfClosed &&
                  (!child.isStartTagClosed || !child.isClosed)))
    }
  }

  private fun checkManifestDocument(root: DOMElement?, collector: XmlDiagnosticCollector) {
    if (root == null || !root.isStartTagClosed || root.tagName != MANIFEST_ROOT_TAG) {
      root?.let {
        collector.errorTag(
            code = CODE_MANIFEST_ROOT,
            message = "The root element of an Android manifest must be <$MANIFEST_ROOT_TAG>",
            element = it,
        )
      }
    }
  }

  /**
   * Checks only parent relationships that Android's manifest schema defines unambiguously.
   * Unknown/new tags are intentionally ignored: Manifest Merger and AAPT2 remain authoritative.
   */
  private fun checkManifestParent(element: DOMElement, collector: XmlDiagnosticCollector) {
    if (!element.isStartTagClosed) {
      return
    }
    val tagName = element.tagName ?: return
    val parentName = (element.parentNode as? DOMElement)?.tagName ?: return
    val expectedParent =
        when {
          tagName in MANIFEST_APPLICATION_COMPONENT_TAGS -> "<$MANIFEST_APPLICATION_TAG>"
          tagName == MANIFEST_INTENT_FILTER_TAG -> "<activity>, <activity-alias>, <service>, or <receiver>"
          tagName in MANIFEST_INTENT_FILTER_CHILD_TAGS -> "<$MANIFEST_INTENT_FILTER_TAG>"
          tagName == MANIFEST_USES_LIBRARY_TAG -> "<$MANIFEST_APPLICATION_TAG>"
          else -> return
        }
    val isValid =
        when (tagName) {
          in MANIFEST_APPLICATION_COMPONENT_TAGS, MANIFEST_USES_LIBRARY_TAG ->
              parentName == MANIFEST_APPLICATION_TAG
          MANIFEST_INTENT_FILTER_TAG -> parentName in MANIFEST_INTENT_FILTER_PARENTS
          else -> parentName == MANIFEST_INTENT_FILTER_TAG
        }
    if (!isValid) {
      collector.errorTag(
          code = CODE_MANIFEST_INVALID_PARENT,
          message = "Manifest <$tagName> must be a child of $expectedParent",
          element = element,
      )
    }
  }

  /**
   * Validates only android: attributes whose enclosing manifest tag has a platform styleable entry.
   * If the table/tag is unavailable, no conclusion can be made and the rule deliberately skips it.
   */
  private fun checkManifestAttributes(element: DOMElement, collector: XmlDiagnosticCollector) {
    if (!element.isStartTagClosed) {
      return
    }
    val tagName = element.tagName ?: return
    val styleables =
        Lookup.getDefault()
            .lookup(ResourceTableRegistry.COMPLETION_MANIFEST_ATTR_RES)
            ?.findPackage(ResourceTableRegistry.PCK_ANDROID)
            ?.findGroup(STYLEABLE)
            ?: return
    val styleable =
        styleables
            .findEntry(manifestStyleableEntryName(tagName))
            ?.findValue(ConfigDescription())
            ?.value as? Styleable
            ?: return
    val allowedAttributes = styleable.entries.mapNotNull { it.name.entry }.toSet()
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
            code = CODE_MANIFEST_UNKNOWN_ATTRIBUTE,
            message = "Unknown attribute '$name' for manifest <$tagName>",
            attribute = attribute,
        )
      }
    }
  }

  private fun manifestStyleableEntryName(tagName: String): String {
    if (tagName == MANIFEST_ROOT_TAG) {
      return MANIFEST_STYLEABLE_PREFIX
    }
    return buildString(MANIFEST_STYLEABLE_PREFIX.length + tagName.length) {
      append(MANIFEST_STYLEABLE_PREFIX)
      var capitalizeNext = true
      tagName.forEach { character ->
        if (character == '-') {
          capitalizeNext = true
        } else {
          append(if (capitalizeNext) character.uppercaseChar() else character)
          capitalizeNext = false
        }
      }
    }
  }

  private fun checkManifestComponentName(element: DOMElement, collector: XmlDiagnosticCollector) {
    if (!element.isStartTagClosed || element.tagName !in MANIFEST_NAMED_COMPONENT_TAGS) {
      return
    }
    val nameAttribute = element.getAttributeNode(ANDROID_NAME_ATTRIBUTE)
    if (nameAttribute == null || nameAttribute.value.isNullOrBlank()) {
      collector.errorTag(
          code = CODE_MANIFEST_MISSING_COMPONENT_NAME,
          message = "Manifest <${element.tagName}> requires an '$ANDROID_NAME_ATTRIBUTE' attribute",
          element = element,
      )
    }
  }

  private fun checkValuesDocument(root: DOMElement?, collector: XmlDiagnosticCollector) {
    if (root == null || !root.isStartTagClosed || root.tagName != VALUES_ROOT_TAG) {
      root?.let {
        collector.errorTag(
            code = CODE_VALUES_ROOT,
            message = "The root element of a values resource file must be <$VALUES_ROOT_TAG>",
            element = it,
        )
      }
      return
    }

    val seen = HashSet<String>()
    root.children.filterIsInstance<DOMElement>().forEach { element ->
      if (!element.isStartTagClosed || element.tagName !in VALUES_NAMED_TAGS) {
        return@forEach
      }
      val nameAttribute = element.getAttributeNode(RESOURCE_NAME_ATTRIBUTE)
      val name = nameAttribute?.value
      if (nameAttribute == null || name.isNullOrBlank()) {
        collector.errorTag(
            code = CODE_VALUES_MISSING_NAME,
            message = "Resource <${element.tagName}> requires a '$RESOURCE_NAME_ATTRIBUTE' attribute",
            element = element,
        )
        return@forEach
      }
      if (!isValidValuesResourceName(name)) {
        collector.errorValue(
            code = CODE_VALUES_INVALID_NAME,
            message = "'$name' is not a valid Android resource name",
            attribute = nameAttribute,
        )
        return@forEach
      }
      val key = "${element.tagName}:$name"
      if (!seen.add(key)) {
        collector.errorValue(
            code = CODE_VALUES_DUPLICATE_NAME,
            message = "Duplicate <${element.tagName}> resource name '$name' in this file",
            attribute = nameAttribute,
        )
      }
    }
  }

  /**
   * Lightweight equivalent of AAPT's resource-entry-name check.
   *
   * The AAPT helper is not exposed through `xml:lsp`'s transitive API classpath, so this uses JDK
   * Unicode identifier predicates and retains AAPT's extra `_`, `.` and `-` allowances.
   */
  private fun isValidValuesResourceName(name: String): Boolean {
    if (name.isEmpty()) {
      return false
    }
    val first = name.codePointAt(0)
    if (!Character.isUnicodeIdentifierStart(first) && first != '_'.code) {
      return false
    }
    var offset = Character.charCount(first)
    while (offset < name.length) {
      val codePoint = name.codePointAt(offset)
      if (!Character.isUnicodeIdentifierPart(codePoint) && codePoint != '.'.code && codePoint != '-'.code) {
        return false
      }
      offset += Character.charCount(codePoint)
    }
    return true
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
    val dependencyParentLayoutAttributes = dependencyParentLayoutAttributes(element)
    val allowedAttributes =
        styleablesFor(widget, element, widgetTable, styleables)
            .flatMapTo(mutableSetOf()) { styleable -> styleable.entries.mapNotNull { it.name.entry } }
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
      // LayoutParams are defined by the parent. For AndroidX/custom parents, only report an
      // android:layout_* attribute when its `SimpleName_Layout` styleable was found in the
      // module/dependency resource snapshots; otherwise the result remains unknown.
      if (
          localName.startsWith(LAYOUT_ATTRIBUTE_PREFIX) &&
              hasNonFrameworkParent(element) &&
              dependencyParentLayoutAttributes.isEmpty()
      ) {
        return@forEach
      }
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

  /**
   * Rejects only complete resource references supplied to attributes whose platform format does not
   * include REFERENCE. More specific resource-type compatibility (for example color vs layout)
   * cannot be inferred safely from AttributeResource.typeMask alone.
   */
  private fun checkLayoutReferenceCompatibility(element: DOMElement, collector: XmlDiagnosticCollector) {
    if (!element.isClosed) {
      return
    }
    val tagName = element.tagName ?: return
    val widgetTable = Lookup.getDefault().lookup(WidgetTable.COMPLETION_LOOKUP_KEY) ?: return
    if (widgetFor(tagName, widgetTable) == null) {
      return
    }
    val attrs =
        Lookup.getDefault()
            .lookup(ResourceTableRegistry.COMPLETION_FRAMEWORK_RES)
            ?.findPackage(ResourceTableRegistry.PCK_ANDROID)
            ?.findGroup(ATTR)
            ?: return
    element.attributeNodes.orEmpty().forEach { attribute ->
      val name = attribute.name ?: return@forEach
      if (!name.startsWith(ANDROID_ATTRIBUTE_PREFIX)) {
        return@forEach
      }
      val reference = XmlResourceReference.parse(attribute.value ?: return@forEach) ?: return@forEach
      // A missing reference already has the more precise AXML003 diagnostic.
      if (resourceResolver.resolve(reference) != XmlResourceResolver.Resolution.Resolved) {
        return@forEach
      }
      val attr =
          attrs.findEntry(name.removePrefix(ANDROID_ATTRIBUTE_PREFIX))
              ?.findValue(ConfigDescription())
              ?.value as? AttributeResource
              ?: return@forEach
      if (!attr.hasAnyType(REFERENCE)) {
        collector.errorValue(
            code = CODE_INCOMPATIBLE_REFERENCE,
            message = "'${reference.text}' is not allowed because '$name' does not accept resource references",
            attribute = attribute,
        )
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

  private fun hasNonFrameworkParent(element: DOMElement): Boolean {
    val parentTag = (element.parentNode as? DOMElement)?.tagName ?: return false
    // Framework class tags can be simple (FrameLayout) or fully qualified android.* names.
    return parentTag.contains('.') && !parentTag.startsWith(ANDROID_VIEW_PACKAGE_PREFIX)
  }

  /**
   * Resolves a non-framework parent's `SimpleName_Layout` styleable from source/dependency resource
   * snapshots. Android libraries publish these through `<declare-styleable>` in their AAR res/values
   * files (for example `CoordinatorLayout_Layout`). If the snapshot has no matching styleable, the
   * caller keeps the existing low-false-positive fallback for `android:layout_*`.
   */
  private fun dependencyParentLayoutAttributes(element: DOMElement): Set<String> {
    val parentTag = (element.parentNode as? DOMElement)?.tagName ?: return emptySet()
    if (!parentTag.contains('.') || parentTag.startsWith(ANDROID_VIEW_PACKAGE_PREFIX)) {
      return emptySet()
    }
    val styleableName = "${parentTag.substringAfterLast('.')}$LAYOUT_SUFFIX"
    val lookup = Lookup.getDefault()
    val tables =
        (lookup.lookup(ResourceTableRegistry.COMPLETION_MODULE_RES).orEmpty() +
                lookup.lookup(ResourceTableRegistry.COMPLETION_DEP_RES).orEmpty())
            .toSet()
    return tables
        .flatMap { table -> table.packages }
        .mapNotNull { resourcePackage ->
          resourcePackage.findGroup(STYLEABLE)?.findEntry(styleableName)
              ?.findValue(ConfigDescription())?.value as? Styleable
        }
        .flatMapTo(mutableSetOf()) { styleable -> styleable.entries.mapNotNull { it.name.entry } }
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
        // FrameLayout, LinearLayout, etc. inherit ViewGroup's own attributes such as
        // clipChildren and clipToPadding, in addition to the base margin LayoutParams.
        addStyleable(styleables, VIEW_GROUP, suffix, result)
        addStyleable(styleables, VIEW_GROUP, MARGIN_LAYOUT_SUFFIX, result)
      }
      widgetTable.getWidget(superclass)?.also { addStyleable(styleables, it.simpleName, suffix, result) }
    }

    if (includeLayoutParams) {
      val parent = node.parentNode as? DOMElement
      if (parent != null) {
        widgetFor(parent.tagName ?: "", widgetTable)?.let {
          result.addAll(styleablesFor(it, parent, widgetTable, styleables, false, LAYOUT_SUFFIX))
        } ?: run {
          addStyleable(styleables, VIEW_GROUP, LAYOUT_SUFFIX, result)
          addStyleable(styleables, VIEW_GROUP, MARGIN_LAYOUT_SUFFIX, result)
          addStyleable(styleables, parent.tagName ?: "", LAYOUT_SUFFIX, result)
        }
      } else {
        // A layout root has DOMDocument as its parent. It still legitimately uses the base
        // ViewGroup LayoutParams attributes, such as android:layout_width and layout_height.
        addStyleable(styleables, VIEW_GROUP, LAYOUT_SUFFIX, result)
        addStyleable(styleables, VIEW_GROUP, MARGIN_LAYOUT_SUFFIX, result)
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

    fun errorRange(code: String, message: String, start: Int, end: Int) {
      val safeStart = start.coerceIn(0, text.length)
      val safeEnd = end.coerceIn(safeStart, text.length)
      add(code, message, ERROR, safeStart, safeEnd)
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
      if (key in keys || diagnostics.size >= MAX_DIAGNOSTICS_PER_FILE) {
        return
      }
      keys.add(key)

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
    private const val MAX_DIAGNOSTICS_PER_FILE = 100
    const val CODE_XML_SYNTAX = "XML001"
    const val CODE_DUPLICATE_ATTRIBUTE = "XML002"
    const val CODE_UNDECLARED_NAMESPACE = "XML003"
    const val CODE_INVALID_ANDROID_NAMESPACE = "XML004"
    const val CODE_UNRESOLVED_RESOURCE = "AXML003"
    const val CODE_UNKNOWN_LAYOUT_TAG = "AXML001"
    const val CODE_UNKNOWN_LAYOUT_ATTRIBUTE = "AXML002"
    const val CODE_INVALID_ATTRIBUTE_VALUE = "AXML004"
    const val CODE_INCOMPATIBLE_REFERENCE = "AXML005"
    const val CODE_VALUES_ROOT = "VALUES001"
    const val CODE_VALUES_MISSING_NAME = "VALUES002"
    const val CODE_VALUES_INVALID_NAME = "VALUES003"
    const val CODE_VALUES_DUPLICATE_NAME = "VALUES004"
    const val CODE_MANIFEST_ROOT = "MANIFEST001"
    const val CODE_MANIFEST_INVALID_PARENT = "MANIFEST002"
    const val CODE_MANIFEST_MISSING_COMPONENT_NAME = "MANIFEST003"
    const val CODE_MANIFEST_UNKNOWN_ATTRIBUTE = "MANIFEST004"
    const val ANDROID_ATTRIBUTE_PREFIX = "android:"
    const val LAYOUT_ATTRIBUTE_PREFIX = "layout_"
    const val ANDROID_VIEW_PACKAGE_PREFIX = "android."
    const val VIEW_GROUP_CLASS = "android.view.ViewGroup"
    const val VIEW_GROUP = "ViewGroup"
    const val LAYOUT_SUFFIX = "_Layout"
    const val MARGIN_LAYOUT_SUFFIX = "_MarginLayout"
    const val VALUES_DIRECTORY = "values"
    const val VALUES_ROOT_TAG = "resources"
    const val RESOURCE_NAME_ATTRIBUTE = "name"
    const val ANDROID_MANIFEST_FILE_NAME = "AndroidManifest.xml"
    const val MANIFEST_STYLEABLE_PREFIX = "AndroidManifest"
    const val MANIFEST_ROOT_TAG = "manifest"
    const val ANDROID_NAME_ATTRIBUTE = "android:name"
    const val MANIFEST_APPLICATION_TAG = "application"
    const val MANIFEST_INTENT_FILTER_TAG = "intent-filter"
    const val MANIFEST_USES_LIBRARY_TAG = "uses-library"
    val MANIFEST_APPLICATION_COMPONENT_TAGS =
        setOf("activity", "activity-alias", "service", "receiver", "provider")
    val MANIFEST_NAMED_COMPONENT_TAGS =
        MANIFEST_APPLICATION_COMPONENT_TAGS + "instrumentation"
    val MANIFEST_INTENT_FILTER_PARENTS =
        setOf("activity", "activity-alias", "service", "receiver")
    val MANIFEST_INTENT_FILTER_CHILD_TAGS = setOf("action", "category", "data")
    val VALUES_NAMED_TAGS =
        setOf(
            "string",
            "color",
            "dimen",
            "bool",
            "integer",
            "fraction",
            "array",
            "integer-array",
            "string-array",
            "plurals",
            "style",
            "attr",
            "declare-styleable",
            "drawable",
            "font",
            "id",
            "macro",
        )
    val LAYOUT_SPECIAL_TAGS = setOf("include", "merge", "view", "fragment", "tag", "layout")
    const val QUOTES = "\"'"
    const val XMLNS_ATTRIBUTE = "xmlns"
    const val XMLNS_PREFIX = "xmlns:"
    const val ANDROID_NAMESPACE_DECLARATION = "xmlns:android"
    const val ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android"
  }
}
