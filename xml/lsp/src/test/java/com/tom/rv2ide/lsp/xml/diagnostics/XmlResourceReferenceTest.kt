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
package com.tom.rv2ide.lsp.xml.diagnostics

import com.android.aaptcompiler.AaptResourceType.ATTR
import com.android.aaptcompiler.AaptResourceType.ID
import com.android.aaptcompiler.AaptResourceType.STRING
import com.android.aaptcompiler.AaptResourceType.STYLE
import com.google.common.truth.Truth.assertThat
import junit.framework.TestCase

class XmlResourceReferenceTest : TestCase() {

  fun testParsesUnqualifiedResourceReference() {
    val reference = XmlResourceReference.parse("@string/title")

    assertThat(reference).isNotNull()
    assertThat(reference!!.packageName).isNull()
    assertThat(reference.type).isEqualTo(STRING)
    assertThat(reference.entry).isEqualTo("title")
    assertThat(reference.isThemeAttribute).isFalse()
  }

  fun testParsesDottedAndHyphenatedResourceEntryNames() {
    val style = XmlResourceReference.parse("@style/TextAppearance.Material3.BodyMedium")
    val qualified = XmlResourceReference.parse("@com.example.lib:style/Theme.Material3-DayNight")

    assertThat(style).isNotNull()
    assertThat(style!!.type).isEqualTo(STYLE)
    assertThat(style.entry).isEqualTo("TextAppearance.Material3.BodyMedium")
    assertThat(qualified).isNotNull()
    assertThat(qualified!!.packageName).isEqualTo("com.example.lib")
    assertThat(qualified.entry).isEqualTo("Theme.Material3-DayNight")
  }

  fun testParsesFrameworkThemeAttributeReference() {
    val reference = XmlResourceReference.parse("?android:attr/textColorPrimary")

    assertThat(reference).isNotNull()
    assertThat(reference!!.packageName).isEqualTo("android")
    assertThat(reference.type).isEqualTo(ATTR)
    assertThat(reference.entry).isEqualTo("textColorPrimary")
    assertThat(reference.isThemeAttribute).isTrue()
  }

  fun testRejectsThemeReferenceToNonAttributeType() {
    assertThat(XmlResourceReference.parse("?color/primary")).isNull()
  }

  fun testParsesIdReferenceAfterCreatingMarkerIsRemoved() {
    val reference = XmlResourceReference.parse("id/title")

    assertThat(reference).isNull()
    assertThat(XmlResourceReference.parse("@id/title")!!.type).isEqualTo(ID)
  }

  fun testTreatsOnlyUnqualifiedMissingIdsAsUnavailable() {
    val resolver = XmlResourceResolver()

    assertThat(resolver.resolutionForMissingReference(XmlResourceReference.parse("@id/editor_toolbar")!!))
        .isEqualTo(XmlResourceResolver.Resolution.Unavailable)
    assertThat(resolver.resolutionForMissingReference(XmlResourceReference.parse("@android:id/content")!!))
        .isEqualTo(XmlResourceResolver.Resolution.NotFound)
    assertThat(resolver.resolutionForMissingReference(XmlResourceReference.parse("@other.package:id/content")!!))
        .isEqualTo(XmlResourceResolver.Resolution.NotFound)
    assertThat(resolver.resolutionForMissingReference(XmlResourceReference.parse("@string/title")!!))
        .isEqualTo(XmlResourceResolver.Resolution.NotFound)
  }

  fun testUsesCompleteModuleIdSnapshotForUnqualifiedIds() {
    val resolver = XmlResourceResolver()
    val available = ModuleResourceIdIndex.Snapshot.Available(setOf("editor_toolbar"))

    assertThat(
            resolver.resolutionForMissingReference(
                XmlResourceReference.parse("@id/editor_toolbar")!!,
                available,
            )
        )
        .isEqualTo(XmlResourceResolver.Resolution.Resolved)
    assertThat(
            resolver.resolutionForMissingReference(
                XmlResourceReference.parse("@id/misspelled_toolbar")!!,
                available,
            )
        )
        .isEqualTo(XmlResourceResolver.Resolution.NotFound)
  }

  fun testCollectsCreatingAndValuesItemIds() {
    val ids =
        ModuleResourceIdIndex.collectIds(
            """
            <resources xmlns:android="http://schemas.android.com/apk/res/android">
              <item type="id" name="declared_item" />
              <View android:id="@+id/created_id" />
            </resources>
            """.trimIndent()
        )

    assertThat(ids).containsExactly("declared_item", "created_id")
    assertThat(ModuleResourceIdIndex.collectIds("<View")).isNull()
  }

  fun testRecognizesSpecialValuesWithoutParsingThemAsResources() {
    assertThat(XmlResourceReference.isSpecialValue("@")).isTrue()
    assertThat(XmlResourceReference.isSpecialValue("@null")).isTrue()
    assertThat(XmlResourceReference.isSpecialValue("@empty")).isTrue()
    assertThat(XmlResourceReference.isSpecialValue("@string/title")).isFalse()

    assertThat(XmlResourceReference.parse("@")).isNull()
    assertThat(XmlResourceReference.parse("@null")).isNull()
    assertThat(XmlResourceReference.parse("@empty")).isNull()
    assertThat(XmlResourceReference.parse("@{viewModel.title}")).isNull()
  }
}
