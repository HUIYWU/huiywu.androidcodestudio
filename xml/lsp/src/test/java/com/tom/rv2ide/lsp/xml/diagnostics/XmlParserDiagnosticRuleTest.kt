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

import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.lsp.xml.diagnostics.rules.XmlParserDiagnosticRule
import java.nio.file.Paths
import junit.framework.TestCase
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager

class XmlParserDiagnosticRuleTest : TestCase() {

  fun testAcceptsWellFormedDocument() {
    assertThat(
            diagnose(
                "<View xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                    "android:id=\"@+id/example\" />"
            )
        )
        .isEmpty()
  }

  fun testReportsSyntaxErrorNotCoveredByTolerantDom() {
    val diagnostics = diagnose("<View><!-- invalid -- comment --></View>")

    assertThat(diagnostics.map { it.code }).containsExactly("XML005")
    assertThat(diagnostics.single().message).isNotEmpty()
  }

  fun testMissingEqualsRangeStaysOnMalformedAttributeInMultilineTag() {
    val text =
        """
        <TextView
          xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_height"40dp"
          android:layout_width="26dp" />
        """.trimIndent()

    val diagnostic = diagnose(text).single { it.code == "XML005" }

    assertThat(diagnostic.range.start.line).isEqualTo(2)
    assertThat(diagnostic.range.end.line).isEqualTo(2)
    assertThat(diagnostic.range.start.column).isEqualTo(2)
    assertThat(diagnostic.range.end.column).isEqualTo(23)
  }

  fun testMissingEqualsRangeStaysOnLaterAttributeInMultilineTag() {
    val text =
        """
        <TextView
          xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_height="40dp"
          android:layout_width"26dp"
          android:id="@+id/symbol" />
        """.trimIndent()

    val diagnostic = diagnose(text).single { it.code == "XML005" }

    assertThat(diagnostic.range.start.line).isEqualTo(3)
    assertThat(diagnostic.range.end.line).isEqualTo(3)
    assertThat(diagnostic.range.start.column).isEqualTo(2)
    assertThat(diagnostic.range.end.column).isEqualTo(22)
  }

  fun testMissingEqualsOnLastAttributeDoesNotMoveToChildTag() {
    val text =
        """
        <Root
          xmlns:tools="http://schemas.android.com/tools"
          tools:context".MainActivity">
          <Child />
        </Root>
        """.trimIndent()

    val diagnostic = diagnose(text).single { it.code == "XML005" }

    assertThat(diagnostic.range.start.line).isEqualTo(2)
    assertThat(diagnostic.range.end.line).isEqualTo(2)
    assertThat(diagnostic.range.start.column).isEqualTo(2)
    assertThat(diagnostic.range.end.column).isEqualTo(15)
  }

  fun testMissingEqualsOnLastSelfClosingAttributeDoesNotMoveToNextTag() {
    val text =
        """
        <Root xmlns:android="http://schemas.android.com/apk/res/android">
          <View
            android:alpha"0.12" />
          <Next />
        </Root>
        """.trimIndent()

    val diagnostic = diagnose(text).single { it.code == "XML005" }

    assertThat(diagnostic.range.start.line).isEqualTo(2)
    assertThat(diagnostic.range.end.line).isEqualTo(2)
    assertThat(diagnostic.range.start.column).isEqualTo(4)
    assertThat(diagnostic.range.end.column).isEqualTo(17)
  }

  fun testDoesNotDuplicateExistingAttributeDiagnostics() {
    assertThat(diagnose("<View id=\"first\" id=\"second\" />")).isEmpty()
    assertThat(diagnose("<View custom:value=\"example\" />")).isEmpty()
  }

  fun testReportsUnboundElementPrefixNotCoveredByXml003() {
    assertThat(diagnose("<custom:View />").map { it.code }).containsExactly("XML005")
  }

  fun testDoesNotDuplicateExistingRecoveryDiagnostics() {
    assertThat(diagnose("<View>")).isEmpty()
  }

  fun testReportsMismatchedClosingTagOnItsName() {
    val diagnostic = diagnose("<LinearLayout></LinearLayou>").single()

    assertThat(diagnostic.code).isEqualTo("XML005")
    assertThat(diagnostic.message)
        .isEqualTo(
            "Closing tag '</LinearLayou>' does not match opening tag '<LinearLayout>'"
        )
    assertThat(diagnostic.range.start.line).isEqualTo(0)
    assertThat(diagnostic.range.start.column).isEqualTo(16)
    assertThat(diagnostic.range.end.line).isEqualTo(0)
    assertThat(diagnostic.range.end.column).isEqualTo(27)
  }

  fun testReportsNestedMismatchAgainstInnermostOpeningTag() {
    val text = "<LinearLayout><TextView></LinearLayout></TextView>"
    val diagnostic = diagnose(text).single()

    assertThat(diagnostic.code).isEqualTo("XML005")
    assertThat(diagnostic.message)
        .isEqualTo(
            "Closing tag '</LinearLayout>' does not match opening tag '<TextView>'"
        )
    assertThat(diagnostic.range.start.column).isEqualTo(26)
    assertThat(diagnostic.range.end.column).isEqualTo(38)
  }

  fun testRuleIsRegisteredBeforeSemanticDocumentRules() {
    assertThat(XmlDiagnosticRuleRegistry.documentRules.first())
        .isSameInstanceAs(XmlParserDiagnosticRule)
  }

  private fun diagnose(text: String) =
      XmlDiagnosticCollector(text).let { collector ->
        XmlParserDiagnosticRule.diagnoseStrictForTest(context(text), collector)
        collector.build()
      }

  private fun context(text: String): XmlDiagnosticContext {
    val document =
        DOMParser.getInstance().parse(text, ANDROID_NAMESPACE, URIResolverExtensionManager())
    return XmlDiagnosticContext.create(
        Paths.get("project/src/main/res/layout/screen.xml"),
        text,
        document,
    )
  }

  private companion object {
    const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
  }
}
