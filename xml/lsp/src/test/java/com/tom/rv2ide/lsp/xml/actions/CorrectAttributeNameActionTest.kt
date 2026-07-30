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
package com.tom.rv2ide.lsp.xml.actions

import com.google.common.truth.Truth.assertThat
import junit.framework.TestCase

class CorrectAttributeNameActionTest : TestCase() {

  fun testSuggestsUniqueCloseFrameworkAttribute() {
    assertThat(
            CorrectAttributeNameAction.findUniqueSuggestion(
                "textColro",
                setOf("textColor", "textSize", "layout_width"),
            )
        )
        .isEqualTo("textColor")
  }

  fun testDoesNotSuggestAmbiguousClosestAttribute() {
    assertThat(
            CorrectAttributeNameAction.findUniqueSuggestion(
                "textColr",
                setOf("textColor", "textColar"),
            )
        )
        .isNull()
  }

  fun testDoesNotSuggestDistantAttribute() {
    assertThat(
            CorrectAttributeNameAction.findUniqueSuggestion(
                "notARealAttribute",
                setOf("textColor", "layout_width"),
            )
        )
        .isNull()
  }

  fun testExtractsOnlyFrameworkAttributeDiagnosticMessage() {
    assertThat(
            CorrectAttributeNameAction.attributeNameFromDiagnostic(
                "Unknown attribute 'android:textColro' for TextView"
            )
        )
        .isEqualTo("android:textColro")
    assertThat(
            CorrectAttributeNameAction.attributeNameFromDiagnostic(
                "Unknown attribute 'app:textColro' for TextView"
            )
        )
        .isNull()
  }
}