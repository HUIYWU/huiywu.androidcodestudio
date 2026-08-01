/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.actions

import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.xml.diagnostics.AttributeValueFixReason
import com.tom.rv2ide.lsp.xml.diagnostics.InvalidAttributeValueDiagnosticData
import com.tom.rv2ide.models.Range
import junit.framework.TestCase

class FixAttributeValueActionTest : TestCase() {

  fun testBuildsReplacementFromStructuredPayloadInsteadOfLocalizedMessage() {
    val diagnostic = diagnostic("AXML004", "任意本地化展示文本").also {
      it.extra =
          InvalidAttributeValueDiagnosticData(
              attributeName = "android:enabled",
              actualValue = "TRUE",
              replacement = "true",
              reason = AttributeValueFixReason.NORMALIZE_BOOLEAN_CASE,
          )
    }

    assertThat(FixAttributeValueAction.replacementFor(diagnostic)).isEqualTo("true")
  }

  fun testAcceptsEachSupportedLosslessFixReason() {
    AttributeValueFixReason.values().forEach { reason ->
      val diagnostic = diagnostic("AXML004", "ignored").also {
        it.extra = InvalidAttributeValueDiagnosticData("android:test", "invalid", "fixed", reason)
      }
      assertThat(FixAttributeValueAction.replacementFor(diagnostic)).isEqualTo("fixed")
    }
  }

  fun testRejectsWrongCodeMissingPayloadAndNoOpReplacement() {
    val payload =
        InvalidAttributeValueDiagnosticData(
            attributeName = "android:enabled",
            actualValue = "TRUE",
            replacement = "true",
            reason = AttributeValueFixReason.NORMALIZE_BOOLEAN_CASE,
        )
    assertThat(FixAttributeValueAction.replacementFor(diagnostic("AXML002", "ignored").also { it.extra = payload }))
        .isNull()
    assertThat(FixAttributeValueAction.replacementFor(diagnostic("AXML004", "ignored"))).isNull()
    assertThat(
            FixAttributeValueAction.replacementFor(
                diagnostic("AXML004", "ignored").also {
                  it.extra = payload.copy(replacement = payload.actualValue)
                }
            )
        )
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
