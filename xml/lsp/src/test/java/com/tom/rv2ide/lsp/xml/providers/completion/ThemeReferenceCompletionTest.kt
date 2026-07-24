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
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 */
package com.tom.rv2ide.lsp.xml.providers.completion

import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.lsp.xml.edits.isAttributeValueReferencePart
import com.tom.rv2ide.xml.resources.ResourceTableRegistry
import junit.framework.TestCase

class ThemeReferenceCompletionTest : TestCase() {

  fun testQuestionMarkOffersLocalAndFrameworkThemeAttributes() {
    assertThat(parseThemeReferenceQueries("?"))
        .containsExactly(
            ThemeReferenceQuery(null, ""),
            ThemeReferenceQuery(ResourceTableRegistry.PCK_ANDROID, ""),
        )
  }

  fun testParsesLocalThemeAttributePrefix() {
    assertThat(parseThemeReferenceQueries("?attr/colorP"))
        .containsExactly(ThemeReferenceQuery(null, "colorP"))
  }

  fun testParsesFrameworkThemeAttributePrefix() {
    assertThat(parseThemeReferenceQueries("?android:attr/textColor"))
        .containsExactly(
            ThemeReferenceQuery(ResourceTableRegistry.PCK_ANDROID, "textColor")
        )
  }

  fun testRejectsNonAttributeThemeReferenceTypes() {
    assertThat(parseThemeReferenceQueries("?string/title")).isEmpty()
    assertThat(parseThemeReferenceQueries("@attr/colorPrimary")).isEmpty()
  }

  fun testEditHandlerTreatsQuestionMarkAsReferenceText() {
    assertThat(isAttributeValueReferencePart('?')).isTrue()
  }
}