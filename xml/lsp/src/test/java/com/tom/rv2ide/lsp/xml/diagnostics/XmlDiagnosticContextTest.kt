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
import java.nio.file.Paths
import junit.framework.TestCase
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager

class XmlDiagnosticContextTest : TestCase() {

  fun testClassifiesAndroidXmlPathsAndCapturesLocalIds() {
    val layoutText =
        """<View xmlns:android="$ANDROID_NAMESPACE" android:id="@+id/local" />"""
    val layout = context("project/src/main/res/layout/screen.xml", layoutText)
    val values = context("project/src/main/res/values/strings.xml", "<resources />")
    val manifest = context("project/src/main/AndroidManifest.xml", "<manifest />")

    assertThat(layout.isLayoutFile).isTrue()
    assertThat(layout.isValuesFile).isFalse()
    assertThat(layout.isManifestFile).isFalse()
    assertThat(layout.declaredIds).containsExactly("local")

    assertThat(values.isValuesFile).isTrue()
    assertThat(values.isLayoutFile).isFalse()
    assertThat(manifest.isManifestFile).isTrue()
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