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

import com.android.aapt.Resources.Attribute.FormatFlags.COLOR
import com.android.aapt.Resources.Attribute.FormatFlags.DIMENSION
import com.android.aapt.Resources.Attribute.FormatFlags.INTEGER
import com.android.aapt.Resources.Attribute.FormatFlags.STRING
import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.lsp.xml.diagnostics.rules.LayoutDiagnosticRule
import junit.framework.TestCase

class LayoutLiteralAttributeValidationTest : TestCase() {

  fun testAcceptsIntegerLiterals() {
    assertValid(INTEGER.number, "0")
    assertValid(INTEGER.number, "-42")
    assertValid(INTEGER.number, "+17")
    assertValid(INTEGER.number, "0x7f")
    assertValid(INTEGER.number, "-0X2A")
  }

  fun testRejectsNonIntegerLiterals() {
    assertInvalid(INTEGER.number, "1.5")
    assertInvalid(INTEGER.number, "12dp")
    assertInvalid(INTEGER.number, "0x")
  }

  fun testAcceptsDimensionLiteralsWithAndroidUnits() {
    listOf("0", "+0", "-0", "0dp", "16dp", "-2.5sp", ".5in", "1e2px", "12dip", "3mm", "4pt")
        .forEach { assertValid(DIMENSION.number, it) }
  }

  fun testRejectsMalformedDimensionLiterals() {
    listOf("16", "-2", "dp", "1.2", "12em", "1dp extra")
        .forEach { assertInvalid(DIMENSION.number, it) }
  }

  fun testAcceptsSupportedInlineColorForms() {
    listOf("#abc", "#1abc", "#AABBCC", "#80AABBCC")
        .forEach { assertValid(COLOR.number, it) }
  }

  fun testRejectsMalformedInlineColors() {
    listOf("red", "#12", "#12345", "#GGHHII", "#123456789")
        .forEach { assertInvalid(COLOR.number, it) }
  }

  fun testLeavesMixedFormatsPermissive() {
    assertValid(INTEGER.number or STRING.number, "not-an-integer")
    assertValid(COLOR.number or STRING.number, "named-color-like-text")
  }

  private fun assertValid(typeMask: Int, value: String) {
    assertThat(LayoutDiagnosticRule.validateLiteralAttributeValue(typeMask, emptySet(), value))
        .isNull()
  }

  private fun assertInvalid(typeMask: Int, value: String) {
    assertThat(LayoutDiagnosticRule.validateLiteralAttributeValue(typeMask, emptySet(), value))
        .isNotNull()
  }
}
