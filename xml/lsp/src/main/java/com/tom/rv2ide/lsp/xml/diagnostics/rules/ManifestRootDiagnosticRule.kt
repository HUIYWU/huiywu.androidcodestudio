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
package com.tom.rv2ide.lsp.xml.diagnostics.rules

import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticCollector
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticContext
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticRule

/** MANIFEST001: validates the Android Manifest document root. */
internal object ManifestRootDiagnosticRule : XmlDiagnosticRule {
  override val id: String = "manifest-root"

  override fun supports(context: XmlDiagnosticContext): Boolean = context.isManifestFile

  override fun diagnose(context: XmlDiagnosticContext, collector: XmlDiagnosticCollector) {
    val root = context.document.documentElement
    // LemMinX represents a valid self-closing <manifest/> with isSelfClosed, without necessarily
    // setting isStartTagClosed. Treat both closed forms as valid.
    if (root == null || (!root.isStartTagClosed && !root.isSelfClosed) || root.tagName != MANIFEST_ROOT_TAG) {
      root?.let {
        collector.errorTag(
            code = CODE_MANIFEST_ROOT,
            message = "The root element of an Android manifest must be <$MANIFEST_ROOT_TAG>",
            element = it,
        )
      }
    }
  }

  private const val CODE_MANIFEST_ROOT = "MANIFEST001"
  private const val MANIFEST_ROOT_TAG = "manifest"
}