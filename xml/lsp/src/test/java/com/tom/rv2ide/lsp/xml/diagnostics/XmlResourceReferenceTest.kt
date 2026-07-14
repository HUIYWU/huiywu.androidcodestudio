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
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XmlResourceReferenceTest {

  @Test
  fun parsesUnqualifiedResourceReference() {
    val reference = XmlResourceReference.parse("@string/title")

    assertThat(reference).isNotNull()
    assertThat(reference!!.packageName).isNull()
    assertThat(reference.type).isEqualTo(STRING)
    assertThat(reference.entry).isEqualTo("title")
    assertThat(reference.isThemeAttribute).isFalse()
  }

  @Test
  fun parsesFrameworkThemeAttributeReference() {
    val reference = XmlResourceReference.parse("?android:attr/textColorPrimary")

    assertThat(reference).isNotNull()
    assertThat(reference!!.packageName).isEqualTo("android")
    assertThat(reference.type).isEqualTo(ATTR)
    assertThat(reference.entry).isEqualTo("textColorPrimary")
    assertThat(reference.isThemeAttribute).isTrue()
  }

  @Test
  fun rejectsThemeReferenceToNonAttributeType() {
    assertThat(XmlResourceReference.parse("?color/primary")).isNull()
  }

  @Test
  fun parsesIdReferenceAfterCreatingMarkerIsRemoved() {
    val reference = XmlResourceReference.parse("id/title")

    assertThat(reference).isNull()
    assertThat(XmlResourceReference.parse("@id/title")!!.type).isEqualTo(ID)
  }

  @Test
  fun rejectsSpecialAndDataBindingValues() {
    assertThat(XmlResourceReference.parse("@null")).isNull()
    assertThat(XmlResourceReference.parse("@empty")).isNull()
    assertThat(XmlResourceReference.parse("@{viewModel.title}")).isNull()
  }
}
