package com.tom.rv2ide.projectdata.gradleedit

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GradleEditTest {
  @Test
  fun kotlinSettingsIncludeIsAddedWithoutTouchingComments() {
    val source = "// include(\":fake\")\ninclude(\":app\")\n"
    val result = ProjectSettingsEditor.addInclude(source, ":feature", kotlinDsl = true)
    val edit = checkNotNull(result as? GradleEditResult.Applied)
    val output = TextEditApplier.apply(source, edit.edits)
    assertThat(output).contains("include(\":feature\")")
    assertThat(ProjectSettingsEditor.findIncludedPaths(output)).containsExactly(":app", ":feature")
    assertThat(output).contains("// include(\":fake\")")
  }

  @Test
  fun groovySingleLineIncludeIsRecognized() {
    val source = "include ':app', ':feature'\n"
    assertThat(ProjectSettingsEditor.findIncludedPaths(source)).containsExactly(":app", ":feature")
    assertThat(ProjectSettingsEditor.addInclude(source, ":feature", kotlinDsl = false))
        .isEqualTo(GradleEditResult.NoChange)
  }

  @Test
  fun projectDirectoryMappingIsReadAndAdded() {
    val source = "include(\":app\")\nproject(\":app\").projectDir = file(\"../app\")\n"
    val mappings = ProjectSettingsEditor.findProjectDirectoryMappings(source)
    assertThat(mappings).containsExactly(
        ProjectSettingsEditor.ProjectDirectoryMapping(":app", "\"../app\""),
    )
    assertThat(ProjectSettingsEditor.addProjectDirMapping(source, ":app", "../other", true))
        .isEqualTo(GradleEditResult.NoChange)
    val nested = "project(\":demo\") {\n  projectDir = file(\"../demo\")\n}\n"
    assertThat(ProjectSettingsEditor.findProjectDirectoryMappings(nested)).containsExactly(
        ProjectSettingsEditor.ProjectDirectoryMapping(":demo", "\"../demo\""),
    )
    val added = ProjectSettingsEditor.addProjectDirMapping(source, ":feature", "../feature", true)
    assertThat(added).isInstanceOf(GradleEditResult.Applied::class.java)
  }

  @Test
  fun kotlinDependencyIsInsertedIntoRealBlock() {
    val source = "val text = \"dependencies { fake }\"\ndependencies {\n  implementation(project(\":app\"))\n}\n"
    val result = BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", ":feature", true)
    val edit = checkNotNull(result as? GradleEditResult.Applied)
    val output = TextEditApplier.apply(source, edit.edits)
    assertThat(output).contains("implementation(project(\":feature\"))")
    assertThat(output).contains("val text = \"dependencies { fake }\"")
  }

  @Test
  fun multipleDependencyBlocksFailClosed() {
    val source = "dependencies { }\ndependencies { }\n"
    assertThat(BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", ":feature", true))
        .isInstanceOf(GradleEditResult.Ambiguous::class.java)
  }

  @Test
  fun crlfIsPreserved() {
    val source = "include(\":app\")\r\n"
    val result = ProjectSettingsEditor.addInclude(source, ":feature", true) as GradleEditResult.Applied
    val output = TextEditApplier.apply(source, result.edits)
    assertThat(output).contains("\r\n")
    assertThat(output.replace("\r\n", "")).doesNotContain("\n")
  }
}