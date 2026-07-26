/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.providers

import com.android.aaptcompiler.AaptResourceType
import com.android.aaptcompiler.ConfigDescription
import com.android.aaptcompiler.Id
import com.android.aaptcompiler.ResourceConfigValue
import com.android.aaptcompiler.ResourceName
import com.android.aaptcompiler.Source
import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.lsp.xml.diagnostics.XmlResourceReference
import com.tom.rv2ide.xml.res.IResourceEntry
import com.tom.rv2ide.xml.res.IResourceGroup
import com.tom.rv2ide.xml.res.IResourceTable
import com.tom.rv2ide.xml.res.IResourceTablePackage
import com.tom.rv2ide.xml.res.ISearchResult
import junit.framework.TestCase
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager

class XmlDefinitionProviderTest : TestCase() {

  fun testFindsCompleteReferenceInAttributeValue() {
    val text =
        """
        <TextView xmlns:android="http://schemas.android.com/apk/res/android"
            android:text="@string/title" />
        """
            .trimIndent()
    val referenceStart = text.indexOf("@string/title")

    assertThat(referenceAt(text, referenceStart)).isEqualTo("@string/title")
    assertThat(referenceAt(text, referenceStart + 7)).isEqualTo("@string/title")
    assertThat(referenceAt(text, referenceStart + "@string/title".length))
        .isEqualTo("@string/title")
  }

  fun testFindsThemeAndQualifiedReferences() {
    val theme = "<View value='?android:attr/colorAccent' />"
    val qualified = "<View value='@com.example.lib:color/brand' />"

    assertThat(referenceAt(theme, theme.indexOf("colorAccent")))
        .isEqualTo("?android:attr/colorAccent")
    assertThat(referenceAt(qualified, qualified.indexOf("brand")))
        .isEqualTo("@com.example.lib:color/brand")
  }

  fun testFindsTrimmedPlainTextReference() {
    val text = "<item>  @color/accent\n</item>"

    assertThat(referenceAt(text, text.indexOf("accent"))).isEqualTo("@color/accent")
  }

  fun testIgnoresCursorOutsideReferenceValue() {
    val text = "<View value='@string/title' other='literal' />"

    assertThat(referenceAt(text, text.indexOf("literal"))).isEqualTo("literal")
    assertThat(referenceAt(text, text.indexOf("View"))).isNull()
  }

  fun testMapsSourcesWithDefaultFirstAndDeduplicatesLocations() {
    val default = value("/project/res/values/strings.xml", 4, ConfigDescription())
    val duplicate =
        value(
            "/project/res/values/strings.xml",
            4,
            ConfigDescription().apply { sdkVersion = 21.toShort() },
        )
    val versioned =
        value(
            "/project/res/values-v21/strings.xml",
            8,
            ConfigDescription().apply { sdkVersion = 21.toShort() },
        )
    val empty = value("", null, ConfigDescription())
    val table = table("com.example", AaptResourceType.STRING, "title", listOf(versioned, empty, duplicate, default))
    val reference = XmlResourceReference.parse("@string/title")!!

    val locations = XmlDefinitionProvider().locationsFor(reference, listOf(table))

    assertThat(locations).hasSize(2)
    assertThat(locations[0].file.toString()).isEqualTo("/project/res/values/strings.xml")
    assertThat(locations[0].range.start.line).isEqualTo(3)
    assertThat(locations[1].file.toString()).isEqualTo("/project/res/values-v21/strings.xml")
    assertThat(locations[1].range.start.line).isEqualTo(7)
  }

  fun testUnqualifiedReferenceDoesNotResolveAndroidPackage() {
    val table = table("android", AaptResourceType.ATTR, "colorAccent", listOf(value("/sdk/attrs.xml", 2)))
    val reference = XmlResourceReference.parse("?attr/colorAccent")!!

    assertThat(XmlDefinitionProvider().locationsFor(reference, listOf(table))).isEmpty()
  }

  private fun value(
      path: String,
      line: Int?,
      config: ConfigDescription = ConfigDescription(),
  ): ResourceConfigValue {
    val value = Id().apply { source = Source(path, line) }
    return ResourceConfigValue(config, "", value)
  }

  private fun table(
      packageName: String,
      type: AaptResourceType,
      entryName: String,
      resourceValues: Collection<ResourceConfigValue>,
  ): IResourceTable {
    val entry =
        object : IResourceEntry {
          override val name = entryName
          override val values = resourceValues
          override fun findValue(config: ConfigDescription, product: String) =
              resourceValues.firstOrNull { it.config == config && it.product == product }
        }
    val group =
        object : IResourceGroup {
          override fun findEntry(name: String, entryId: Short?) = entry.takeIf { name == entryName }
          override fun findEntries(entryId: Short?, predicate: (String) -> Boolean) =
              listOf(entry).filter { predicate(it.name) }
        }
    val resourcePackage =
        object : IResourceTablePackage {
          override val name = packageName
          override fun findGroup(requestedType: AaptResourceType, groupId: Byte?) =
              group.takeIf { requestedType == type }
        }
    return object : IResourceTable {
      override val packages = listOf(resourcePackage)
      override fun findResource(name: ResourceName): ISearchResult? = null
      override fun findPackage(name: String) = resourcePackage.takeIf { name == packageName }
    }
  }

  private fun referenceAt(text: String, cursor: Int): String? {
    val document =
        DOMParser.getInstance().parse(text, ANDROID_NAMESPACE, URIResolverExtensionManager())
    return XmlDefinitionProvider().referenceAt(document, text, cursor)
  }

  private companion object {
    const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
  }
}
