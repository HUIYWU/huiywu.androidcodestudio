/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.providers.completion

import com.android.aaptcompiler.AaptResourceType.ATTR
import com.android.aaptcompiler.AaptResourceType.COLOR
import com.android.aaptcompiler.AaptResourceType.STRING
import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.xml.resources.ResourceDefinition
import com.tom.rv2ide.lsp.xml.resources.ResourceDefinitionKind
import java.nio.file.Paths
import junit.framework.TestCase

class WorkspaceResourceCompletionTest : TestCase() {

  fun testParsesLocalResourceAndThemeQueries() {
    assertThat(parseWorkspaceResourceCompletionQuery("@string/ti"))
        .isEqualTo(WorkspaceResourceCompletionQuery('@', STRING, "ti"))
    assertThat(parseWorkspaceResourceCompletionQuery("?attr/co"))
        .isEqualTo(WorkspaceResourceCompletionQuery('?', ATTR, "co"))
  }

  fun testRejectsCreatingQualifiedAndInvalidThemeQueries() {
    assertThat(parseWorkspaceResourceCompletionQuery("@+id/title")).isNull()
    assertThat(parseWorkspaceResourceCompletionQuery("@android:string/ok")).isNull()
    assertThat(parseWorkspaceResourceCompletionQuery("@example.lib:string/title")).isNull()
    assertThat(parseWorkspaceResourceCompletionQuery("?android:attr/colorAccent")).isNull()
    assertThat(parseWorkspaceResourceCompletionQuery("?string/title")).isNull()
    assertThat(parseWorkspaceResourceCompletionQuery("@")).isNull()
  }

  fun testFiltersByTypeAndPrefixAndDeduplicatesQualifiedDefinitions() {
    val query = checkNotNull(parseWorkspaceResourceCompletionQuery("@string/ti"))
    val definitions =
        listOf(
            definition(STRING, "title", "values/strings.xml"),
            definition(STRING, "title", "values-v31/strings.xml"),
            definition(STRING, "timestamp", "values/strings.xml"),
            definition(STRING, "other", "values/strings.xml"),
            definition(COLOR, "title", "values/colors.xml"),
        )

    assertThat(workspaceResourceCompletionCandidates(query, definitions))
        .containsExactly(
            WorkspaceResourceCompletionCandidate('@', STRING, "timestamp"),
            WorkspaceResourceCompletionCandidate('@', STRING, "title"),
        )
        .inOrder()
  }

  fun testKeepsThemeMarkerForWorkspaceAttrCandidates() {
    val query = checkNotNull(parseWorkspaceResourceCompletionQuery("?attr/cor"))

    assertThat(workspaceResourceCompletionCandidates(query, listOf(definition(ATTR, "corner_radius", "values/attrs.xml"))))
        .containsExactly(WorkspaceResourceCompletionCandidate('?', ATTR, "corner_radius"))
  }

  fun testMergesByInsertTextAndKeepsExistingTableCandidate() {
    val tableTitle = item("@string/title", "resource table")
    val tableOther = item("@string/other", "resource table")
    val workspaceTitle = item("@string/title", "workspace")
    val workspaceNew = item("@string/new_title", "workspace")

    val result =
        mergeWorkspaceResourceCompletions(
            CompletionResult(listOf(tableTitle, tableOther)),
            listOf(workspaceTitle, workspaceNew),
        )

    assertThat(result.items.map { it.insertText })
        .containsExactly("@string/new_title", "@string/other", "@string/title")
    assertThat(result.items.single { it.insertText == "@string/title" }.detail)
        .isEqualTo("resource table")
  }

  private fun definition(
      type: com.android.aaptcompiler.AaptResourceType,
      name: String,
      file: String,
  ) =
      ResourceDefinition(
          type = type,
          name = name,
          sourceFile = Paths.get("project/app/src/main/res/$file"),
          nameRange = null,
          kind = ResourceDefinitionKind.VALUE_ELEMENT,
      )

  private fun item(insertText: String, detail: String) =
      CompletionItem().apply {
        ideLabel = insertText
        this.detail = detail
        this.insertText = insertText
      }
}
