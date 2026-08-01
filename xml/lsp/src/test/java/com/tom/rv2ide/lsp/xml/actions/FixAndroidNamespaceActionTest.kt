/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.actions

import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.lsp.xml.diagnostics.InvalidAndroidNamespaceDiagnosticData
import com.tom.rv2ide.lsp.xml.diagnostics.MissingAndroidNamespaceDiagnosticData
import junit.framework.TestCase

class FixAndroidNamespaceActionTest : TestCase() {

  fun testMissingAndroidNamespacePayloadUsesOnlyAndroidPrefix() {
    assertThat(MissingAndroidNamespaceDiagnosticData().prefix).isEqualTo("android")
    assertThat(MissingAndroidNamespaceDiagnosticData("app").prefix).isEqualTo("app")
  }

  fun testInvalidAndroidNamespacePayloadKeepsActualAndExpectedUris() {
    val facts =
        InvalidAndroidNamespaceDiagnosticData(
            actualUri = "http://schemas.android.com/apk/res/androdi",
            expectedUri = FixAndroidNamespaceAction.ANDROID_NAMESPACE_URI,
        )

    assertThat(facts.actualUri).isEqualTo("http://schemas.android.com/apk/res/androdi")
    assertThat(facts.expectedUri).isEqualTo(FixAndroidNamespaceAction.ANDROID_NAMESPACE_URI)
  }

  fun testBuildsInsertionAfterRootTagName() {
    val edit = FixAndroidNamespaceAction.namespaceInsertion("<LinearLayout\n    android:id=\"@+id/root\" />")

    assertThat(edit).isNotNull()
    assertThat(edit!!.range.start.line).isEqualTo(0)
    assertThat(edit.range.start.column).isEqualTo("<LinearLayout".length)
    assertThat(edit.newText)
        .isEqualTo("\n    xmlns:android=\"${FixAndroidNamespaceAction.ANDROID_NAMESPACE_URI}\"")
  }

  fun testBuildsInsertionAtMultilineRootTagName() {
    val text = "\n<LinearLayout\n    android:id=\"@+id/root\" />"
    val edit = FixAndroidNamespaceAction.namespaceInsertion(text)

    assertThat(edit).isNotNull()
    assertThat(edit!!.range.start.line).isEqualTo(1)
    assertThat(edit.range.start.column).isEqualTo("<LinearLayout".length)
  }

  fun testDoesNotInsertDuplicateOrMalformedRootDeclaration() {
    assertThat(
            FixAndroidNamespaceAction.namespaceInsertion(
                "<View xmlns:android=\"${FixAndroidNamespaceAction.ANDROID_NAMESPACE_URI}\" />"
            )
        )
        .isNull()
    assertThat(FixAndroidNamespaceAction.namespaceInsertion("<!-- comment -->")).isNull()
    assertThat(FixAndroidNamespaceAction.namespaceInsertion("<View")).isNull()
  }
}