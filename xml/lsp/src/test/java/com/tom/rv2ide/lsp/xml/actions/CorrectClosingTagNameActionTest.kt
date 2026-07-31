/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.actions

import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.lsp.xml.diagnostics.ClosingTagMismatchDiagnosticData
import junit.framework.TestCase

class CorrectClosingTagNameActionTest : TestCase() {

  fun testUsesStructuredMismatchDataContract() {
    val data = ClosingTagMismatchDiagnosticData("LinearLayou", "LinearLayout")

    assertThat(data.actualName).isEqualTo("LinearLayou")
    assertThat(data.expectedName).isEqualTo("LinearLayout")
  }
}
