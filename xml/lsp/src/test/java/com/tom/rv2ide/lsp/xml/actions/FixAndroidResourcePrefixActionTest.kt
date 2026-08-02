/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.actions

import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.xml.diagnostics.MissingFrameworkResourcePrefixDiagnosticData
import com.tom.rv2ide.models.Range
import junit.framework.TestCase

class FixAndroidResourcePrefixActionTest : TestCase() {

  fun testUsesStructuredFrameworkReplacementInsteadOfLocalizedMessage() {
    val diagnostic = diagnostic("AXML003", "任意本地化展示文本").also {
      it.extra =
          MissingFrameworkResourcePrefixDiagnosticData(
              originalReference = "@color/white",
              replacement = "@android:color/white",
          )
    }

    assertThat(FixAndroidResourcePrefixAction.replacementFor(diagnostic))
        .isEqualTo("@android:color/white")
  }

  fun testRejectsWrongCodeMissingPayloadAndNoOpReplacement() {
    val payload =
        MissingFrameworkResourcePrefixDiagnosticData(
            originalReference = "@string/ok",
            replacement = "@android:string/ok",
        )
    assertThat(
            FixAndroidResourcePrefixAction.replacementFor(
                diagnostic("AXML004", "ignored").also { it.extra = payload }
            )
        )
        .isNull()
    assertThat(FixAndroidResourcePrefixAction.replacementFor(diagnostic("AXML003", "ignored"))).isNull()
    assertThat(
            FixAndroidResourcePrefixAction.replacementFor(
                diagnostic("AXML003", "ignored").also {
                  it.extra = payload.copy(replacement = payload.originalReference)
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