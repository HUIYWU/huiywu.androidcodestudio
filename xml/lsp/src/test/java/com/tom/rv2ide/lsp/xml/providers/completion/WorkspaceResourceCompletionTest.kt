/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.providers.completion

import com.android.aaptcompiler.AaptResourceType.ID
import com.android.aaptcompiler.AaptResourceType.STRING
import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.xml.resources.ResourceDefinition
import com.tom.rv2ide.lsp.xml.resources.ResourceDefinitionKind
import java.nio.file.Paths
import junit.framework.TestCase

class WorkspaceResourceCompletionTest : TestCase() {

  fun testParsesOnlyUnqualifiedIdQueries() {
    assertThat(parseCreatingIdCompletionQuery("@id/edi"))
        .isEqualTo(WorkspaceResourceCompletionQuery('@', ID, "edi"))
  }

  fun testRejectsNonIdCreatingQualifiedAndInvalidQueries() {
    assertThat(parseCreatingIdCompletionQuery("@+id/title")).isNull()
    assertThat(parseCreatingIdCompletionQuery("@string/title")).isNull()
    assertThat(parseCreatingIdCompletionQuery("@android:id/content")).isNull()
    assertThat(parseCreatingIdCompletionQuery("?attr/colorAccent")).isNull()
    assertThat(parseCreatingIdCompletionQuery("@")).isNull()
  }

  fun testFiltersToCreatingIdDeclarationsAndDeduplicates() {
    val query = checkNotNull(parseCreatingIdCompletionQuery("@id/too"))
    val definitions =
        listOf(
            definition(ID, "toolbar", "layout/first.xml", ResourceDefinitionKind.CREATING_ID_DECLARATION),
            definition(ID, "toolbar", "layout/second.xml", ResourceDefinitionKind.CREATING_ID_DECLARATION),
            definition(ID, "tool_panel", "layout/second.xml", ResourceDefinitionKind.CREATING_ID_DECLARATION),
            definition(ID, "tool_value", "values/ids.xml", ResourceDefinitionKind.ID_DECLARATION),
            definition(ID, "tool_values_creating", "values/ids.xml", ResourceDefinitionKind.CREATING_ID_DECLARATION),
            definition(STRING, "toolbar", "values/strings.xml", ResourceDefinitionKind.VALUE_ELEMENT),
        )

    assertThat(creatingIdCompletionCandidates(query, definitions))
        .containsExactly(
            WorkspaceResourceCompletionCandidate('@', ID, "tool_panel"),
            WorkspaceResourceCompletionCandidate('@', ID, "toolbar"),
        )
        .inOrder()
  }

  fun testDoesNotUseCreatingIdsForOtherQueryKinds() {
    val definitions = listOf(definition(ID, "toolbar", "layout/first.xml", ResourceDefinitionKind.CREATING_ID_DECLARATION))

    assertThat(creatingIdCompletionCandidates(WorkspaceResourceCompletionQuery('@', STRING, "too"), definitions))
        .isEmpty()
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
      kind: ResourceDefinitionKind,
  ) =
      ResourceDefinition(
          type = type,
          name = name,
          sourceFile = Paths.get("project/app/src/main/res/$file"),
          nameRange = null,
          kind = kind,
      )

  private fun item(insertText: String, detail: String) =
      CompletionItem().apply {
        ideLabel = insertText
        this.detail = detail
        this.insertText = insertText
      }
}
