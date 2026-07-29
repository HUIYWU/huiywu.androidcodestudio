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
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
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

class XmlHoverProviderTest : TestCase() {

  fun testConvertsLineAndColumnToDocumentOffset() {
    val text = "<View>\n  @string/title\n</View>"
    val provider = XmlHoverProvider()

    assertThat(provider.offsetAt(text, 1, 2)).isEqualTo(text.indexOf("@string/title"))
    assertThat(provider.offsetAt(text, 1, 15)).isEqualTo(text.indexOf('\n', text.indexOf('\n') + 1))
    assertThat(provider.offsetAt(text, 1, 16)).isNull()
    assertThat(provider.offsetAt(text, 9, 0)).isNull()
  }

  fun testFindsCandidatesWithDefaultConfigurationFirst() {
    val default = value("/project/res/values/strings.xml", 4, ConfigDescription())
    val versioned =
        value(
            "/project/res/values-v21/strings.xml",
            8,
            ConfigDescription().apply { sdkVersion = 21.toShort() },
        )
    val table = table("com.example", AaptResourceType.STRING, "title", listOf(versioned, default))
    val reference = XmlResourceReference.parse("@string/title")!!

    val candidates = XmlHoverProvider().candidatesFor(reference, listOf(table))

    assertThat(candidates).hasSize(2)
    assertThat(candidates[0].configuration).isEqualTo("default")
    assertThat(candidates[0].source).isEqualTo("/project/res/values/strings.xml")
    assertThat(candidates[0].line).isEqualTo(4)
    assertThat(candidates[0].valueSummary).isEqualTo("ID")
    assertThat(candidates[1].configuration).isEqualTo("v21")
  }

  fun testUnqualifiedReferenceDoesNotUseAndroidPackage() {
    val table = table("android", AaptResourceType.ATTR, "colorAccent", listOf(value("/sdk/attrs.xml", 2)))
    val reference = XmlResourceReference.parse("?attr/colorAccent")!!

    assertThat(XmlHoverProvider().candidatesFor(reference, listOf(table))).isEmpty()
  }

  fun testShortensProjectAndIdeHomeSourcePaths() {
    val provider = XmlHoverProvider()

    assertThat(
            provider.displaySource(
                "/storage/emulated/0/AndroidIDEProjects/MyBasicActivity/app/src/main/res/values/strings.xml",
                2,
                "/storage/emulated/0/AndroidIDEProjects/MyBasicActivity",
            )
        )
        .isEqualTo("<root>/app/src/main/res/values/strings.xml:2")
    assertThat(
            provider.displaySource(
                "/data/user/0/com.tom.rv2ide/files/home/.gradle/caches/9.0.0/transforms/hash/transformed/appcompat-1.7.1/res/values/values.xml",
                2114,
                "/storage/emulated/0/AndroidIDEProjects/MyBasicActivity",
            )
        )
        .isEqualTo("\$HOME/.gradle/caches/9.0.0/transforms/hash/transformed/appcompat-1.7.1/res/values/values.xml:2114")
    assertThat(provider.displaySource("/opt/android-sdk/platforms/android-35/data/res/values/strings.xml", null, null))
        .isEqualTo("/opt/android-sdk/platforms/android-35/data/res/values/strings.xml")
  }

  fun testFormatsResourceMetadataAndLimitsConfigurations() {
    val provider = XmlHoverProvider()
    val reference = XmlResourceReference.parse("@style/TextAppearance.Material3.BodyMedium")!!
    val candidates =
        (0 until 6).map { index ->
          XmlHoverProvider.ResourceHoverCandidate(
              packageName = "com.example",
              configuration = if (index == 0) "default" else "v${20 + index}",
              source = "/project/res/values/styles.xml",
              line = 4 + index,
              valueSummary = "parent=style/Parent, 3 items",
          )
        }

    val content = provider.formatHover(reference, candidates, "/project")

    assertThat(content).startsWith("@style/TextAppearance.Material3.BodyMedium\n\n---\n\n")
    assertThat(content).contains("Package: `com.example`")
    assertThat(content).contains("Configuration: `default`")
    assertThat(content).contains("Value: `parent=style/Parent, 3 items`")
    assertThat(content).contains("Source: `<root>/res/values/styles.xml:4`")
    assertThat(content).contains("2 more configurations are available.")
    assertThat(content).doesNotContain("Configuration: `v25`")
    assertThat(content).doesNotContain("**")
    assertThat(content).doesNotContain("##")
    assertThat(content).doesNotContain("- ")
    assertThat(content).doesNotContain("```")
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
}