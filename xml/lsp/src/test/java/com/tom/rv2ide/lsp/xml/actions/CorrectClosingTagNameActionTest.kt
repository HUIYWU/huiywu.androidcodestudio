/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.actions

import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.xml.diagnostics.ClosingTagMismatchDiagnosticData
import com.tom.rv2ide.models.Range
import junit.framework.TestCase

class CorrectClosingTagNameActionTest : TestCase() {

  fun testUsesStructuredMismatchDataContract() {
    val data = ClosingTagMismatchDiagnosticData("LinearLayou", "LinearLayout")

    assertThat(data.actualName).isEqualTo("LinearLayou")
    assertThat(data.expectedName).isEqualTo("LinearLayout")
  }

  fun testBuildsReplacementFromStructuredPayloadInsteadOfLocalizedMessage() {
    val diagnostic =
        diagnostic("XML005", "任意本地化展示文本").also {
          it.extra = ClosingTagMismatchDiagnosticData("LinearLayout", "View")
        }

    assertThat(CorrectClosingTagNameAction.replacementFor(diagnostic)).isEqualTo("View")
  }

  fun testRejectsXml005WithoutMismatchPayload() {
    assertThat(
            CorrectClosingTagNameAction.replacementFor(diagnostic("XML005", "Closing tag text")))
        .isNull()
  }

  private fun diagnostic(code: String, message: String) =
      DiagnosticItem(
          message = message,
          code = code,
          range = Range.NONE,
          source = "test",
          severity = DiagnosticSeverity.ERROR,
      )
}
