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

class CorrectTagNameActionTest : TestCase() {

  fun testExtractsSimpleUnknownLayoutTagDiagnosticMessage() {
    assertThat(CorrectTagNameAction.tagNameFromDiagnostic("Unknown layout tag 'TextVeiw'"))
        .isEqualTo("TextVeiw")
  }

  fun testRejectsNonLayoutOrQualifiedTagDiagnosticMessage() {
    assertThat(CorrectTagNameAction.tagNameFromDiagnostic("Unknown attribute 'android:textColro'"))
        .isNull()
    assertThat(
            CorrectTagNameAction.tagNameFromDiagnostic(
                "Unknown layout tag 'example.CustomVeiw'"
            )
        )
        .isNull()
  }

  fun testAmbiguousWidgetSuggestionRemainsUnavailable() {
    assertThat(
            CorrectAttributeNameAction.findUniqueSuggestion(
                "Viewx",
                setOf("View", "Views"),
            )
        )
        .isNull()
  }
}