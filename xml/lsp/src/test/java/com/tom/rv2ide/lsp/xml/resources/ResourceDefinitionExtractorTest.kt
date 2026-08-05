/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.resources
import com.android.aaptcompiler.AaptResourceType.ATTR
import com.android.aaptcompiler.AaptResourceType.COLOR

import com.android.aaptcompiler.AaptResourceType.DRAWABLE
import com.android.aaptcompiler.AaptResourceType.ID
import com.android.aaptcompiler.AaptResourceType.LAYOUT
import com.android.aaptcompiler.AaptResourceType.STRING
import com.google.common.truth.Truth.assertThat
import java.nio.file.Paths
import junit.framework.TestCase

class ResourceDefinitionExtractorTest : TestCase() {

  fun testExtractsSupportedValuesDefinitionsWithExactNameRanges() {
    val text =
        """
        <resources>
          <string name="title">Title</string>
          <color name="primary">#00ff00</color>
          <item type="id" name="content" />
          <declare-styleable name="Widget" />
          <item type="string" name="ignored" />
        </resources>
        """
            .trimIndent()
    val definitions = extract("project/app/src/main/res/values/values.xml", text)

    assertThat(definitions.map { it.type to it.name })
        .containsExactly(STRING to "title", COLOR to "primary", ID to "content")
        .inOrder()
    assertThat(definitions.map { it.kind })
        .containsExactly(
            ResourceDefinitionKind.VALUE_ELEMENT,
            ResourceDefinitionKind.VALUE_ELEMENT,
            ResourceDefinitionKind.ID_DECLARATION,
        )
    assertNameRange(text, definitions[0], "title")
    assertNameRange(text, definitions[1], "primary")
    assertNameRange(text, definitions[2], "content")
  }

  fun testExtractsAttrDefinitionsButNotDeclareStyleableNames() {
    val text =
        "<resources><attr name=\"brand_color\" format=\"color\" /><declare-styleable name=\"Widget\"><attr name=\"corner_radius\" format=\"dimension\" /></declare-styleable></resources>"

    val definitions = extract("project/app/src/main/res/values/attrs.xml", text)

    assertThat(definitions.map { it.type to it.name })
        .containsExactly(ATTR to "brand_color", ATTR to "corner_radius")
        .inOrder()
    assertNameRange(text, definitions[0], "brand_color")
    assertNameRange(text, definitions[1], "corner_radius")
  }

  fun testExtractsFileResourcesFromQualifiedDirectoriesWithoutTextRange() {
    val layout = extract("project/app/src/main/res/layout-land/activity_main.xml", "<broken")
    val drawable = extract("project/app/src/main/res/drawable-v24/ic_logo.xml", "")

    assertThat(layout).hasSize(1)
    assertThat(layout.single().type).isEqualTo(LAYOUT)
    assertThat(layout.single().name).isEqualTo("activity_main")
    assertThat(layout.single().kind).isEqualTo(ResourceDefinitionKind.FILE_RESOURCE)
    assertThat(layout.single().nameRange).isNull()

    assertThat(drawable).hasSize(1)
    assertThat(drawable.single().type).isEqualTo(DRAWABLE)
    assertThat(drawable.single().name).isEqualTo("ic_logo")
    assertThat(drawable.single().nameRange).isNull()
  }

  fun testExtractsCreatingIdsFromCompleteFileResourceXml() {
    val text =
        """
        <View xmlns:android="http://schemas.android.com/apk/res/android"
            android:id="@+id/content"
            android:label="@+android:id/framework" />
        """
            .trimIndent()
    val definitions = extract("project/app/src/main/res/layout/screen.xml", text)

    assertThat(definitions.map { it.type to it.name })
        .containsExactly(LAYOUT to "screen", ID to "content")
        .inOrder()
    assertThat(definitions[1].kind).isEqualTo(ResourceDefinitionKind.ID_DECLARATION)
    assertNameRange(text, definitions[1], "content")
  }

  fun testMeasuredExtractionKeepsValuesAndFileResultsEquivalent() {
    val values = Paths.get("project/app/src/main/res/values/strings.xml")
    val valuesText = "<resources><string name=\"title\">Title</string></resources>"
    val measuredValues = ResourceDefinitionExtractor.extractMeasured(values, valuesText)

    assertThat(measuredValues.extraction).isEqualTo(ResourceDefinitionExtractor.extract(values, valuesText))
    val valuesTiming = checkNotNull(measuredValues.valuesTiming)
    assertThat(valuesTiming.domParseNanos).isAtLeast(0L)
    assertThat(valuesTiming.syntaxRecoveryNanos).isAtLeast(0L)
    assertThat(valuesTiming.elementTraversalNanos).isAtLeast(0L)
    assertThat(valuesTiming.creatingIdNanos).isAtLeast(0L)

    val layout = Paths.get("project/app/src/main/res/layout/screen.xml")
    val layoutText = "<View />"
    val measuredLayout = ResourceDefinitionExtractor.extractMeasured(layout, layoutText)

    assertThat(measuredLayout.extraction).isEqualTo(ResourceDefinitionExtractor.extract(layout, layoutText))
    assertThat(measuredLayout.valuesTiming).isNull()
  }

  fun testExtractsCreatingIdsFromValuesWithExactNameRanges() {
    val text =
        """
        <resources>
          <item type="id" name="content" />
          <string name="label" android:id="@+id/label_view">Label</string>
        </resources>
        """
            .trimIndent()
    val definitions = extract("project/app/src/main/res/values/references.xml", text)

    assertThat(definitions.map { it.name }).containsExactly("content", "label", "label_view").inOrder()
    assertNameRange(text, definitions[2], "label_view")
  }

  fun testClassifiesPathsWithTheSameRulesAsExtraction() {
    assertThat(ResourceDefinitionExtractor.categoryOf(Paths.get("project/app/src/main/res/values-v31/strings.xml")))
        .isEqualTo(ResourceDefinitionExtractor.Category.VALUES)
    assertThat(ResourceDefinitionExtractor.categoryOf(Paths.get("project/app/src/main/res/layout-land/screen.xml")))
        .isEqualTo(ResourceDefinitionExtractor.Category.FILE)
    assertThat(ResourceDefinitionExtractor.categoryOf(Paths.get("project/app/src/main/assets/screen.xml")))
        .isEqualTo(ResourceDefinitionExtractor.Category.NONE)
  }

  fun testIgnoresNonResourceAndInvalidFileNames() {
    assertThat(extract("project/src/main/layout/screen.xml", "<View />")).isEmpty()
    assertThat(extract("project/src/main/res/layout/Bad-Name.xml", "<View />")).isEmpty()
    assertThat(extract("project/src/main/res/values/strings.xml", "<not-resources />")).isEmpty()
  }

  fun testTreatsMalformedValuesDocumentAsUnavailable() {
    val result =
        ResourceDefinitionExtractor.extract(
            Paths.get("project/src/main/res/values/strings.xml"),
            "<resources><string name=\"title\">Title",
        )

    assertThat(result).isEqualTo(ResourceDefinitionExtractor.Extraction.Unavailable)
  }

  fun testSnapshotKeepsQualifiedDefinitionsAndFailsClosedForUnavailableFile() {
    val base = Paths.get("project/app/src/main/res/values/strings.xml")
    val qualified = Paths.get("project/app/src/main/res/values-v31/strings.xml")
    val usageOnly = Paths.get("project/app/src/main/res/layout/screen.xml")
    val baseEntry = ResourceFileEntry.create(base, "<resources><string name=\"title\">Base</string></resources>")
    val qualifiedEntry = ResourceFileEntry.create(qualified, "<resources><string name=\"title\">Qualified</string></resources>")
    val usageOnlyEntry = ResourceFileEntry.create(usageOnly, "<View android:text=\"@string/title\" />")

    val available = snapshotEntries(
        linkedMapOf(base to baseEntry, qualified to qualifiedEntry, usageOnly to usageOnlyEntry)
    )
    assertThat(available).isInstanceOf(ResourceSnapshot.Available::class.java)
    val snapshot = available as ResourceSnapshot.Available
    // A layout with no values declaration is still the @layout/screen file-resource definition.
    assertThat(snapshot.definitions.map { it.sourceFile }).containsExactly(base, qualified, usageOnly).inOrder()
    assertThat(snapshot.files).containsExactly(base, qualified, usageOnly)

    assertThat(snapshot.occurrencesByFile.getValue(usageOnly).map { it.reference.entry })
        .containsExactly("title")
    assertThat(snapshotEntries(mapOf(base to ResourceFileEntry.Unavailable)))
        .isEqualTo(ResourceSnapshot.Unavailable)
  }

  private fun extract(path: String, text: String): List<ResourceDefinition> {
    val result = ResourceDefinitionExtractor.extract(Paths.get(path), text)
    assertThat(result).isInstanceOf(ResourceDefinitionExtractor.Extraction.Available::class.java)
    return (result as ResourceDefinitionExtractor.Extraction.Available).definitions
  }

  private fun assertNameRange(text: String, definition: ResourceDefinition, expected: String) {
    val range = checkNotNull(definition.nameRange)
    val offset = text.indexOf(expected)
    val lineStart = text.lastIndexOf('\n', offset - 1) + 1
    assertThat(range.start.line).isEqualTo(text.substring(0, offset).count { it == '\n' })
    assertThat(range.start.column).isEqualTo(offset - lineStart)
    assertThat(range.end.line).isEqualTo(range.start.line)
    assertThat(range.end.column - range.start.column).isEqualTo(expected.length)
  }
}
