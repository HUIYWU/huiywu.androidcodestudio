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

class WorkspaceResourceDefinitionExtractorTest : TestCase() {

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
            WorkspaceResourceDefinitionKind.VALUE_ELEMENT,
            WorkspaceResourceDefinitionKind.VALUE_ELEMENT,
            WorkspaceResourceDefinitionKind.VALUE_ELEMENT,
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
    assertThat(layout.single().kind).isEqualTo(WorkspaceResourceDefinitionKind.FILE_RESOURCE)
    assertThat(layout.single().nameRange).isNull()

    assertThat(drawable).hasSize(1)
    assertThat(drawable.single().type).isEqualTo(DRAWABLE)
    assertThat(drawable.single().name).isEqualTo("ic_logo")
    assertThat(drawable.single().nameRange).isNull()
  }

  fun testIgnoresNonResourceAndInvalidFileNames() {
    assertThat(extract("project/src/main/layout/screen.xml", "<View />")).isEmpty()
    assertThat(extract("project/src/main/res/layout/Bad-Name.xml", "<View />")).isEmpty()
    assertThat(extract("project/src/main/res/values/strings.xml", "<not-resources />")).isEmpty()
  }

  fun testTreatsMalformedValuesDocumentAsUnavailable() {
    val result =
        WorkspaceResourceDefinitionExtractor.extract(
            Paths.get("project/src/main/res/values/strings.xml"),
            "<resources><string name=\"title\">Title",
        )

    assertThat(result).isEqualTo(WorkspaceResourceDefinitionExtractor.Extraction.Unavailable)
  }

  private fun extract(path: String, text: String): List<WorkspaceResourceDefinition> {
    val result = WorkspaceResourceDefinitionExtractor.extract(Paths.get(path), text)
    assertThat(result).isInstanceOf(WorkspaceResourceDefinitionExtractor.Extraction.Available::class.java)
    return (result as WorkspaceResourceDefinitionExtractor.Extraction.Available).definitions
  }

  private fun assertNameRange(text: String, definition: WorkspaceResourceDefinition, expected: String) {
    val range = checkNotNull(definition.nameRange)
    val offset = text.indexOf(expected)
    val lineStart = text.lastIndexOf('\n', offset - 1) + 1
    assertThat(range.start.line).isEqualTo(text.substring(0, offset).count { it == '\n' })
    assertThat(range.start.column).isEqualTo(offset - lineStart)
    assertThat(range.end.line).isEqualTo(range.start.line)
    assertThat(range.end.column - range.start.column).isEqualTo(expected.length)
  }
}
