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
import com.tom.rv2ide.lsp.xml.diagnostics.rules.ResourceReferenceDiagnosticRule
import junit.framework.TestCase
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.dom.DOMText
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager

class XmlTextResourceReferenceTest : TestCase() {

  fun testRecognizesOnlyCompletePlainTextReferenceWithExactRange() {
    val source = fixtureText("diagnostics/text_resource_references.xml")
    val document =
        DOMParser.getInstance()
            .parse(source, ANDROID_NAMESPACE_URI_FOR_TEST, URIResolverExtensionManager())
    val textNodes = mutableListOf<DOMText>()

    fun collect(node: org.eclipse.lemminx.dom.DOMNode) {
      if (node is DOMText) {
        textNodes.add(node)
      }
      node.children.forEach(::collect)
    }
    collect(document)

    val candidates =
        textNodes.mapNotNull(ResourceReferenceDiagnosticRule::textResourceReferenceCandidate)

    assertThat(candidates).hasSize(1)
    val candidate = candidates.single()
    assertThat(candidate.reference.text).isEqualTo("@string/missing")
    assertThat(source.substring(candidate.start, candidate.end)).isEqualTo("@string/missing")
  }

  private fun fixtureText(path: String): String {
    return checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
          "Missing test fixture: $path"
        }
        .bufferedReader()
        .use { it.readText() }
  }

  private companion object {
    const val ANDROID_NAMESPACE_URI_FOR_TEST = "http://schemas.android.com/apk/res/android"
  }
}