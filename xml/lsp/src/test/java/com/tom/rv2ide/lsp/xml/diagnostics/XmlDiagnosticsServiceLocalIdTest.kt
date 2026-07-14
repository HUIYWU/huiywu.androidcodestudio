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
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager
import org.junit.Test

class XmlDiagnosticsServiceLocalIdTest {

  @Test
  fun collectsCreatingIdsFromFixtureForLaterLocalReferences() {
    val document =
        DOMParser.getInstance()
            .parse(
                fixtureText("diagnostics/local_id_references.xml"),
                ANDROID_NAMESPACE_URI,
                URIResolverExtensionManager(),
            )

    val declaredIds = XmlDiagnosticsService().collectLocalIdDeclarations(document)

    assertThat(declaredIds).containsExactly("title", "action")
  }

  @Test
  fun excludesQualifiedCreatingIdsFromLocalDeclarations() {
    val document =
        DOMParser.getInstance()
            .parse(
                """
                <View xmlns:android="http://schemas.android.com/apk/res/android"
                    android:id="@+id/local"
                    android:tag="@+android:id/framework"
                    android:contentDescription="@+other.package:id/external" />
                """.trimIndent(),
                ANDROID_NAMESPACE_URI,
                URIResolverExtensionManager(),
            )

    val declaredIds = XmlDiagnosticsService().collectLocalIdDeclarations(document)

    assertThat(declaredIds).containsExactly("local")
  }

  private fun fixtureText(path: String): String {
    return checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
          "Missing test fixture: $path"
        }
        .bufferedReader()
        .use { it.readText() }
  }
}