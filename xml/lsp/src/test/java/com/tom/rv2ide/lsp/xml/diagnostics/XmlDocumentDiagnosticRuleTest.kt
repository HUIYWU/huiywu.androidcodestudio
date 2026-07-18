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
import com.tom.rv2ide.lsp.xml.diagnostics.rules.CommonXmlElementDiagnosticRule
import com.tom.rv2ide.lsp.xml.diagnostics.rules.ManifestDiagnosticRule
import com.tom.rv2ide.lsp.xml.diagnostics.rules.ValuesDocumentDiagnosticRule
import java.nio.file.Paths
import junit.framework.TestCase
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager

class XmlDocumentDiagnosticRuleTest : TestCase() {

  fun testValuesRuleReportsNameProblemsAndDuplicate() {
    val text =
        """
        <resources>
          <string>missing</string>
          <string name="1invalid">invalid</string>
          <string name="valid">first</string>
          <string name="valid">second</string>
        </resources>
        """.trimIndent()
    val context = context("project/src/main/res/values/strings.xml", text)
    val collector = XmlDiagnosticCollector(text)

    assertThat(ValuesDocumentDiagnosticRule.supports(context)).isTrue()
    ValuesDocumentDiagnosticRule.diagnose(context, collector)

    assertThat(collector.build().map { it.code })
        .containsExactly("VALUES002", "VALUES003", "VALUES004")
        .inOrder()
  }

  fun testManifestRootRuleAcceptsSelfClosingManifestAndRejectsOtherRoot() {
    val valid = context("project/src/main/AndroidManifest.xml", "<manifest />")
    val validCollector = XmlDiagnosticCollector(valid.text)
    ManifestDiagnosticRule.diagnose(valid, validCollector)
    assertThat(validCollector.build()).isEmpty()

    val invalid = context("project/src/main/AndroidManifest.xml", "<resources />")
    val invalidCollector = XmlDiagnosticCollector(invalid.text)
    assertThat(ManifestDiagnosticRule.supports(invalid)).isTrue()
    ManifestDiagnosticRule.diagnose(invalid, invalidCollector)
    assertThat(invalidCollector.build().map { it.code }).containsExactly("MANIFEST001")
  }

  fun testCommonElementRuleReportsDuplicatePrefixAndAndroidNamespaceProblems() {
    val text =
        """<View xmlns:android="wrong" custom:value="x" android:id="one" android:id="two" />"""
    val context = context("project/src/main/res/layout/screen.xml", text)
    val collector = XmlDiagnosticCollector(text)

    CommonXmlElementDiagnosticRule.diagnose(
        context.document.documentElement,
        context,
        collector,
    )

    assertThat(collector.build().map { it.code })
        .containsExactly("XML004", "XML003", "XML002")
  }

  fun testRulesIgnoreUnsupportedDocumentKinds() {
    val layout = context("project/src/main/res/layout/screen.xml", "<View />")

    assertThat(ValuesDocumentDiagnosticRule.supports(layout)).isFalse()
    assertThat(ManifestDiagnosticRule.supports(layout)).isFalse()
  }

  private fun context(path: String, text: String): XmlDiagnosticContext {
    val document =
        DOMParser.getInstance().parse(text, ANDROID_NAMESPACE, URIResolverExtensionManager())
    return XmlDiagnosticContext.create(Paths.get(path), text, document)
  }

  private companion object {
    const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
  }
}