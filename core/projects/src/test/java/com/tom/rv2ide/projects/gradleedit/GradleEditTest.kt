package com.tom.rv2ide.projects.gradleedit

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GradleEditTest {
  @Test fun kotlinSettingsIncludeIsAddedWithoutTouchingComments() {
    val source = "// include(\":fake\")\ninclude(\":app\")\n"
    val output = apply(source, ProjectSettingsEditor.addInclude(source, ":feature", true))
    assertThat(output).contains("include(\":feature\")")
    assertThat(ProjectSettingsEditor.findIncludedPaths(output)).containsExactly(":app", ":feature")
    assertThat(output).contains("// include(\":fake\")")
  }

  @Test fun standaloneIncludeIsRemovedAndCompoundIncludeEntryCanBeRemoved() {
    val source = "include(\":app\")\ninclude(\":feature\", \":shared\")\n"
    val output = apply(source, ProjectSettingsEditor.removeInclude(source, ":app"))
    assertThat(ProjectSettingsEditor.findIncludedPaths(output)).containsExactly(":feature", ":shared")
    val compoundOutput = apply(source, ProjectSettingsEditor.removeInclude(source, ":feature"))
    assertThat(compoundOutput).contains("include(\":shared\")")
    assertThat(compoundOutput).doesNotContain(":feature")
  }

  @Test fun multilineKotlinIncludeRemovesOneEntryAndPreservesOthers() {
    val source = """include(
    ":app",
    ":tewo",
    ":sec",
    ":profile1",
    ":profile2"
)
"""
    val output = apply(source, ProjectSettingsEditor.removeInclude(source, ":profile2"))
    assertThat(output).doesNotContain(":profile2")
    assertThat(output).isEqualTo("include(\n    \":app\",\n    \":tewo\",\n    \":sec\",\n    \":profile1\"\n)\n")
    assertThat(ProjectSettingsEditor.findIncludedPaths(output)).containsExactly(":app", ":tewo", ":sec", ":profile1")
  }

  @Test fun includeWithDynamicArgumentFailsClosed() {
    val source = "include(\":app\", modules.first(), \":profile2\")\n"
    assertThat(ProjectSettingsEditor.removeInclude(source, ":profile2"))
        .isInstanceOf(GradleEditResult.Unsupported::class.java)
  }

  @Test fun repeatedIncludeCallsFailClosed() {
    val source = "include(\":profile2\")\ninclude(\":app\", \":profile2\")\n"
    assertThat(ProjectSettingsEditor.removeInclude(source, ":profile2"))
        .isInstanceOf(GradleEditResult.Ambiguous::class.java)
  }

  @Test fun projectDirectoryMappingCanBeUpdatedAndRemoved() {
    val source = "project(\":app\").projectDir = file(\"../app\")\n"
    val updated = apply(source, ProjectSettingsEditor.updateProjectDirMapping(source, ":app", "../custom-app", true))
    assertThat(ProjectSettingsEditor.findProjectDirectoryMappings(updated)).containsExactly(ProjectSettingsEditor.ProjectDirectoryMapping(":app", "\"../custom-app\""))
    val removed = apply(updated, ProjectSettingsEditor.removeProjectDirMapping(updated, ":app"))
    assertThat(removed).isEmpty()
  }

  @Test fun nestedProjectDirectoryMappingCanBeUpdated() {
    val source = "project(\":demo\") {\n  projectDir = file(\"../demo\")\n}\n"
    val output = apply(source, ProjectSettingsEditor.updateProjectDirMapping(source, ":demo", "../new-demo", true))
    assertThat(output).contains("file(\"../new-demo\")")
  }

  @Test fun kotlinDependencyIsInsertedIntoRealBlock() {
    val source = "val text = \"dependencies { fake }\"\ndependencies {\n  implementation(project(\":app\"))\n}\n"
    val output = apply(source, BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", ":feature", true))
    assertThat(output).contains("implementation(project(\":feature\"))")
    assertThat(output).contains("val text = \"dependencies { fake }\"")
  }

  @Test fun supportedAndUnsupportedDependencyFormsAreDistinguished() {
    val supported = "dependencies { implementation(project(\":feature\")) }"
    val unsupported = "dependencies { implementation(project(path = \":feature\")) }"
    val commented = "// implementation(project(path = \":feature\"))\ndependencies { }"
    assertThat(BuildScriptDependenciesEditor.findProjectDependencies(supported)).containsExactly(
        BuildScriptDependenciesEditor.ProjectDependency("implementation", ":feature"),
    )
    assertThat(BuildScriptDependenciesEditor.hasUnsupportedProjectDependencyReference(supported, ":feature")).isFalse()
    assertThat(BuildScriptDependenciesEditor.hasUnsupportedProjectDependencyReference(unsupported, ":feature")).isTrue()
    assertThat(BuildScriptDependenciesEditor.hasUnsupportedProjectDependencyReference(commented, ":feature")).isFalse()
  }

  @Test fun projectDependencyCanBeRenamedAndRemoved() {
    val source = "dependencies {\n  implementation(project(\":old\"))\n  api project(':api-old')\n}\n"
    val renamed = apply(source, BuildScriptDependenciesEditor.renameProjectDependency(source, "implementation", ":old", ":new"))
    assertThat(renamed).contains("project(\":new\")")
    val removed = apply(renamed, BuildScriptDependenciesEditor.removeProjectDependency(renamed, "api", ":api-old"))
    assertThat(removed).doesNotContain(":api-old")
  }

  @Test fun transactionRestoresFilesAndOnlyDeletesTrackedDirectories() {
    val root = createTempDir(prefix = "project-edit-")
    try {
      val settings = root.resolve("settings.gradle.kts").apply { writeText("include(\":app\")\n") }
      val newModule = root.resolve("feature")
      val transaction = ProjectEditTransaction.begin(root)
      transaction.capture(settings)
      transaction.trackCreatedDirectory(newModule)
      settings.writeText("changed")
      newModule.mkdirs()
      newModule.resolve("build.gradle.kts").writeText("plugins {}")
      assertThat(transaction.rollback()).isEmpty()
      assertThat(settings.readText()).isEqualTo("include(\":app\")\n")
      assertThat(newModule.exists()).isFalse()
    } finally {
      root.deleteRecursively()
    }
  }

  @Test fun multipleDependencyBlocksFailClosed() {
    val source = "dependencies { }\ndependencies { }\n"
    assertThat(BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", ":feature", true)).isInstanceOf(GradleEditResult.Ambiguous::class.java)
  }

  @Test fun crlfIsPreserved() {
    val source = "include(\":app\")\r\n"
    val output = apply(source, ProjectSettingsEditor.addInclude(source, ":feature", true))
    assertThat(output).contains("\r\n")
    assertThat(output.replace("\r\n", "")).doesNotContain("\n")
  }

  private fun apply(source: String, result: GradleEditResult): String = TextEditApplier.apply(source, checkNotNull(result as? GradleEditResult.Applied).edits)
}