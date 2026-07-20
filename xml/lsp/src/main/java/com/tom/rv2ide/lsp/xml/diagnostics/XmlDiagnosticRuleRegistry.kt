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

import com.tom.rv2ide.lsp.xml.diagnostics.rules.CommonXmlElementDiagnosticRule
import com.tom.rv2ide.lsp.xml.diagnostics.rules.LayoutDiagnosticRule
import com.tom.rv2ide.lsp.xml.diagnostics.rules.ManifestDiagnosticRule
import com.tom.rv2ide.lsp.xml.diagnostics.rules.ResourceReferenceDiagnosticRule
import com.tom.rv2ide.lsp.xml.diagnostics.rules.ValuesDocumentDiagnosticRule
import com.tom.rv2ide.lsp.xml.diagnostics.rules.XmlParserDiagnosticRule

/** Fixed rule registry; avoids runtime service discovery on the editor's real-time path. */
internal object XmlDiagnosticRuleRegistry {
  val documentRules: List<XmlDiagnosticRule> =
      listOf(
          XmlParserDiagnosticRule,
          ValuesDocumentDiagnosticRule,
          ManifestDiagnosticRule,
      )

  val elementRecoveryRules: List<XmlElementRecoveryDiagnosticRule> =
      listOf(CommonXmlElementDiagnosticRule)

  val elementRules: List<XmlElementDiagnosticRule> =
      listOf(
          CommonXmlElementDiagnosticRule,
          ManifestDiagnosticRule,
          LayoutDiagnosticRule,
          ResourceReferenceDiagnosticRule,
      )

  val textRules: List<XmlTextDiagnosticRule> = listOf(ResourceReferenceDiagnosticRule)
}