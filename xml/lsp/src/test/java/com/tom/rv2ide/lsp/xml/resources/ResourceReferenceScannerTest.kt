/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.resources

import com.android.aaptcompiler.AaptResourceType.COLOR
import com.android.aaptcompiler.AaptResourceType.ID
import com.android.aaptcompiler.AaptResourceType.STRING
import com.google.common.truth.Truth.assertThat
import junit.framework.TestCase

class ResourceReferenceScannerTest : TestCase() {

  fun testScansCompleteAttributeAndPlainTextReferencesWithExactRanges() {
    val text =
        """
        <View xmlns:android="http://schemas.android.com/apk/res/android"
            android:text="@string/title"
            android:id="@+id/content">
          @color/primary
        </View>
        """
            .trimIndent()

    val occurrences = scan(text)

    assertThat(occurrences.map { it.reference.type to it.reference.entry })
        .containsExactly(STRING to "title", ID to "content", COLOR to "primary")
        .inOrder()
    assertThat(occurrences.map { it.isCreatingId }).containsExactly(false, true, false).inOrder()
    assertOccurrenceRange(text, occurrences[0], "@string/title")
    assertOccurrenceRange(text, occurrences[1], "@+id/content")
    assertOccurrenceRange(text, occurrences[2], "@color/primary")
    assertThat(occurrences[1].reference.text).isEqualTo("@id/content")
  }

  fun testMeasuredScanKeepsResultsAndCountsEquivalent() {
    val text =
        """
        <View android:text="@string/title" android:id="@+id/content">
          @color/primary
        </View>
        """
            .trimIndent()

    val measured = ResourceReferenceScanner.scanMeasured(text)

    assertThat(measured.scan).isEqualTo(ResourceReferenceScanner.scan(text))
    assertThat(measured.timing.domParseNanos).isAtLeast(0L)
    assertThat(measured.timing.syntaxRecoveryNanos).isAtLeast(0L)
    assertThat(measured.timing.traversalNanos).isAtLeast(0L)
    assertThat(measured.timing.positionIndexNanos).isAtLeast(0L)
    assertThat(measured.timing.referenceParseNanos).isAtLeast(0L)
    assertThat(measured.timing.occurrenceBuildNanos).isAtLeast(0L)
    assertThat(measured.timing.sortNanos).isAtLeast(0L)
    assertThat(measured.timing.attributeOccurrences).isEqualTo(2)
    assertThat(measured.timing.textOccurrences).isEqualTo(1)
    assertThat(measured.timing.creatingIdOccurrences).isEqualTo(1)
  }

  fun testPreservesMultilineOccurrencePositions() {
    val text =
        """
        <View
            android:text="@string/title">
          @color/primary
        </View>
        """
            .trimIndent()
    val occurrences = scan(text)

    assertThat(occurrences.map { it.reference.type to it.reference.entry })
        .containsExactly(STRING to "title", COLOR to "primary")
        .inOrder()
    assertThat(occurrences[0].range.start.line).isEqualTo(1)
    assertThat(occurrences[0].range.start.column).isEqualTo(18)
    assertThat(occurrences[1].range.start.line).isEqualTo(2)
    assertThat(occurrences[1].range.start.column).isEqualTo(2)
  }

  fun testSkipsToolsDataBindingSpecialAndPartialReferences() {
    val text =
        """
        <View xmlns:tools="http://schemas.android.com/tools"
            tools:text="@string/tools_only"
            android:text="@{viewModel.title}"
            android:tag="prefix @string/not_complete"
            android:contentDescription="@null">
          text @string/not_complete
        </View>
        """
            .trimIndent()

    assertThat(scan(text)).isEmpty()
  }

  fun testFastRejectsPlainValuesAndKeepsThemeReferences() {
    val text =
        """
        <View
            android:layout_width="match_parent"
            android:tag="plain_value"
            android:textColor="?attr/textColorPrimary" />
        """
            .trimIndent()

    val occurrences = scan(text)

    assertThat(occurrences).hasSize(1)
    assertThat(occurrences.single().reference.entry).isEqualTo("textColorPrimary")
    assertThat(occurrences.single().reference.isThemeAttribute).isTrue()
    assertOccurrenceRange(text, occurrences.single(), "?attr/textColorPrimary")
  }

  fun testScansResAutoCustomAttributeNamesOnly() {
    val text =
        "<View xmlns:android=\"http://schemas.android.com/apk/res/android\" xmlns:custom=\"http://schemas.android.com/apk/res-auto\" xmlns:tools=\"http://schemas.android.com/tools\" xmlns:lib=\"http://schemas.android.com/apk/res/example.lib\" custom:brand_color=\"plain\" android:brand_color=\"plain\" tools:brand_color=\"plain\" lib:brand_color=\"plain\" />"

    val occurrences = scan(text)

    assertThat(occurrences).hasSize(1)
    assertThat(occurrences.single().reference.type).isEqualTo(com.android.aaptcompiler.AaptResourceType.ATTR)
    assertThat(occurrences.single().reference.entry).isEqualTo("brand_color")
    assertOccurrenceRange(text, occurrences.single(), "custom:brand_color")
  }

  fun testTargetAtAcceptsOnlyReferenceRange() {
    val text = "<View android:text=\"@string/title\" />"
    val referenceOffset = text.indexOf("title") + 2

    val target = ResourceReferenceScanner.targetAt(text, referenceOffset)

    assertThat(target).isNotNull()
    assertThat(target!!.reference.type).isEqualTo(STRING)
    assertThat(ResourceReferenceScanner.targetAt(text, text.indexOf("android:text"))).isNull()
    assertThat(ResourceReferenceScanner.targetAt(text, text.indexOf('"'))).isNull()
  }

  fun testMalformedDocumentIsUnavailable() {
    assertThat(ResourceReferenceScanner.scan("<View android:text=\"@string/title\">"))
        .isEqualTo(ResourceReferenceScanner.ScanResult.Unavailable)
  }

  private fun scan(text: String): List<ResourceReferenceOccurrence> {
    val result = ResourceReferenceScanner.scan(text)
    assertThat(result).isInstanceOf(ResourceReferenceScanner.ScanResult.Available::class.java)
    return (result as ResourceReferenceScanner.ScanResult.Available).occurrences
  }

  private fun assertOccurrenceRange(
      text: String,
      occurrence: ResourceReferenceOccurrence,
      expected: String,
  ) {
    val offset = text.indexOf(expected)
    assertThat(occurrence.startOffset).isEqualTo(offset)
    assertThat(occurrence.endOffset).isEqualTo(offset + expected.length)
    assertThat(occurrence.range.end.column - occurrence.range.start.column).isEqualTo(expected.length)
  }
}