/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation.
 */
package com.tom.rv2ide.lsp.xml.actions

import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.lsp.xml.diagnostics.ClosingTagMismatchDiagnosticData
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticMessages
import java.util.Locale
import junit.framework.TestCase

class CorrectClosingTagNameActionTest : TestCase() {

  fun testUsesStructuredMismatchDataContract() {
    val data = ClosingTagMismatchDiagnosticData("LinearLayou", "LinearLayout")

    assertThat(data.actualName).isEqualTo("LinearLayou")
    assertThat(data.expectedName).isEqualTo("LinearLayout")
  }

  fun testUsesShortLocalizedActionLabels() {
    assertThat(XmlDiagnosticMessages.fixClosingTag(Locale.ENGLISH)).isEqualTo("Fix closing tag")
    assertThat(XmlDiagnosticMessages.fixClosingTag(Locale.CHINESE)).isEqualTo("修正结束标签")
  }

  fun testDiagnosticMessagesAreLocalizedWithoutChangingData() {
    assertThat(
        XmlDiagnosticMessages.closingTagMismatch("LinearLayou", "LinearLayout", Locale.ENGLISH)
    ).isEqualTo("Closing tag '</LinearLayou>' does not match opening tag '<LinearLayout>'")
    assertThat(
        XmlDiagnosticMessages.closingTagMismatch("LinearLayou", "LinearLayout", Locale.CHINESE)
    ).isEqualTo("结束标签 '</LinearLayou>' 与开始标签 '<LinearLayout>' 不匹配")
  }
}