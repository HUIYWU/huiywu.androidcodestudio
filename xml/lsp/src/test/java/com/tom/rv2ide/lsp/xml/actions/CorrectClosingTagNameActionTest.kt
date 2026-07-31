/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation.
 */
package com.tom.rv2ide.lsp.xml.actions

import com.google.common.truth.Truth.assertThat
import junit.framework.TestCase

class CorrectClosingTagNameActionTest : TestCase() {

  fun testExtractsExpectedAndActualNames() {
    assertThat(
        CorrectClosingTagNameAction.mismatchNamesFromDiagnostic(
            "Closing tag '</LinearLayou>' does not match opening tag '<LinearLayout>'"
        )
    ).isEqualTo(CorrectClosingTagNameAction.MismatchNames("LinearLayou", "LinearLayout"))
  }

  fun testRejectsOtherDiagnosticMessages() {
    assertThat(
        CorrectClosingTagNameAction.mismatchNamesFromDiagnostic(
            "Element 'LinearLayout' is missing an end tag"
        )
    ).isNull()
  }

  fun testAcceptsQualifiedTagNames() {
    assertThat(
        CorrectClosingTagNameAction.mismatchNamesFromDiagnostic(
            "Closing tag '</androidx.cardview.widget.CardView>' does not match opening tag '<com.google.android.material.card.MaterialCardView>'"
        )?.expectedName
    ).isEqualTo("com.google.android.material.card.MaterialCardView")
  }
}