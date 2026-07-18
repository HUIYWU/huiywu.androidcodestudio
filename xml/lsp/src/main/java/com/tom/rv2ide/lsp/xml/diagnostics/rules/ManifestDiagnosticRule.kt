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

import com.android.aaptcompiler.AaptResourceType.STYLEABLE
import com.android.aaptcompiler.ConfigDescription
import com.android.aaptcompiler.Styleable
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticCollector
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticContext
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticRule
import com.tom.rv2ide.lsp.xml.diagnostics.XmlElementDiagnosticRule
import com.tom.rv2ide.xml.resources.ResourceTableRegistry
import org.eclipse.lemminx.dom.DOMElement

/** MANIFEST001-MANIFEST004: conservative Android Manifest structure and attribute checks. */
internal object ManifestDiagnosticRule : XmlDiagnosticRule, XmlElementDiagnosticRule {
  override val id: String = "manifest"

  override fun supports(context: XmlDiagnosticContext): Boolean = context.isManifestFile

  /** MANIFEST001 document-root check. */
  override fun diagnose(context: XmlDiagnosticContext, collector: XmlDiagnosticCollector) {
    val root = context.document.documentElement
    if (root == null || (!root.isStartTagClosed && !root.isSelfClosed) || root.tagName != MANIFEST_ROOT_TAG) {
      root?.let {
        collector.errorTag(
            code = CODE_MANIFEST_ROOT,
            message = "The root element of an Android manifest must be <$MANIFEST_ROOT_TAG>",
            element = it,
        )
      }
    }
  }

  /** MANIFEST002-MANIFEST004 element checks. */
  override fun diagnose(
      element: DOMElement,
      context: XmlDiagnosticContext,
      collector: XmlDiagnosticCollector,
  ) {
    checkParent(element, collector)
    checkAttributes(element, collector)
    checkComponentName(element, collector)
  }

  /** Checks only parent relationships defined unambiguously by the Manifest schema. */
  private fun checkParent(element: DOMElement, collector: XmlDiagnosticCollector) {
    if (!element.isStartTagClosed) {
      return
    }
    val tagName = element.tagName ?: return
    val parentName = (element.parentNode as? DOMElement)?.tagName ?: return
    val expectedParent =
        when {
          tagName in APPLICATION_COMPONENT_TAGS -> "<$APPLICATION_TAG>"
          tagName == INTENT_FILTER_TAG ->
              "<activity>, <activity-alias>, <service>, <receiver>, or <provider>"
          tagName in INTENT_FILTER_CHILD_TAGS -> "<$INTENT_FILTER_TAG>"
          tagName == USES_LIBRARY_TAG -> "<$APPLICATION_TAG>"
          else -> return
        }
    val isValid =
        when (tagName) {
          in APPLICATION_COMPONENT_TAGS, USES_LIBRARY_TAG -> parentName == APPLICATION_TAG
          INTENT_FILTER_TAG -> parentName in INTENT_FILTER_PARENTS
          else -> parentName == INTENT_FILTER_TAG
        }
    if (!isValid) {
      collector.errorTag(
          code = CODE_MANIFEST_INVALID_PARENT,
          message = "Manifest <$tagName> must be a child of $expectedParent",
          element = element,
      )
    }
  }

  /** Skips safely when the platform Manifest styleable table or the tag entry is unavailable. */
  private fun checkAttributes(element: DOMElement, collector: XmlDiagnosticCollector) {
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
            .findEntry(styleableEntryName(tagName))
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
      if (name.removePrefix(ANDROID_ATTRIBUTE_PREFIX) !in allowedAttributes) {
        collector.error(
            code = CODE_MANIFEST_UNKNOWN_ATTRIBUTE,
            message = "Unknown attribute '$name' for manifest <$tagName>",
            attribute = attribute,
        )
      }
    }
  }

  private fun styleableEntryName(tagName: String): String {
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

  private fun checkComponentName(element: DOMElement, collector: XmlDiagnosticCollector) {
    if (!element.isStartTagClosed || element.tagName !in NAMED_COMPONENT_TAGS) {
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

  private const val CODE_MANIFEST_ROOT = "MANIFEST001"
  private const val CODE_MANIFEST_INVALID_PARENT = "MANIFEST002"
  private const val CODE_MANIFEST_MISSING_COMPONENT_NAME = "MANIFEST003"
  private const val CODE_MANIFEST_UNKNOWN_ATTRIBUTE = "MANIFEST004"
  private const val MANIFEST_ROOT_TAG = "manifest"
  private const val MANIFEST_STYLEABLE_PREFIX = "AndroidManifest"
  private const val ANDROID_ATTRIBUTE_PREFIX = "android:"
  private const val ANDROID_NAME_ATTRIBUTE = "android:name"
  private const val APPLICATION_TAG = "application"
  private const val INTENT_FILTER_TAG = "intent-filter"
  private const val USES_LIBRARY_TAG = "uses-library"
  private val APPLICATION_COMPONENT_TAGS =
      setOf("activity", "activity-alias", "service", "receiver", "provider")
  private val NAMED_COMPONENT_TAGS = APPLICATION_COMPONENT_TAGS + "instrumentation"
  private val INTENT_FILTER_PARENTS =
      setOf("activity", "activity-alias", "service", "receiver", "provider")
  private val INTENT_FILTER_CHILD_TAGS = setOf("action", "category", "data")
}