/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.resources

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
    val baseExtraction = ResourceDefinitionExtractor.extract(base, "<resources><string name=\"title\">Base</string></resources>")
    val qualifiedExtraction = ResourceDefinitionExtractor.extract(qualified, "<resources><string name=\"title\">Qualified</string></resources>")
    val usageOnlyExtraction = ResourceDefinitionExtractor.extract(usageOnly, "<View android:text=\"@string/title\" />")

    val available = snapshotDefinitions(
        linkedMapOf(base to baseExtraction, qualified to qualifiedExtraction, usageOnly to usageOnlyExtraction)
    )
    assertThat(available).isInstanceOf(ResourceSnapshot.Available::class.java)
    val snapshot = available as ResourceSnapshot.Available
    // A layout with no values declaration is still the @layout/screen file-resource definition.
    assertThat(snapshot.definitions.map { it.sourceFile }).containsExactly(base, qualified, usageOnly).inOrder()
    assertThat(snapshot.files).containsExactly(base, qualified, usageOnly)

    assertThat(snapshotDefinitions(mapOf(base to ResourceDefinitionExtractor.Extraction.Unavailable)))
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
