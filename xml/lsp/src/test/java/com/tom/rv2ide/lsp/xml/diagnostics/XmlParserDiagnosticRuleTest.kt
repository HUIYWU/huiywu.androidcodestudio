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

  fun testRuleIsRegisteredBeforeSemanticDocumentRules() {
    assertThat(XmlDiagnosticRuleRegistry.documentRules.first())
        .isSameInstanceAs(XmlParserDiagnosticRule)
  }

  private fun diagnose(text: String) =
      XmlDiagnosticCollector(text).let { collector ->
        XmlParserDiagnosticRule.diagnose(context(text), collector)
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
