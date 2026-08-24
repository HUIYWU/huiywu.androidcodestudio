package com.tom.rv2ide.projects.gradleedit
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.treesitter.TreeSitter
import java.util.concurrent.TimeUnit
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class GradleEditTest {
  @get:Rule
  val testTimeout: Timeout = Timeout.builder()
      .withTimeout(30, TimeUnit.SECONDS)
      .withLookingForStuckThread(true)
      .build()

  companion object {
    @JvmStatic
    @BeforeClass
    fun loadTreeSitterNativeLibraries() {
      TreeSitter.loadLibrary()
      System.loadLibrary("tree-sitter-kotlin")
      System.loadLibrary("tree-sitter-groovy")
    }
  }

  @Test fun kotlinSettingsIncludeIsAddedWithoutTouchingComments() {
    val source = "// include(\":fake\")\ninclude(\":app\")\n"
    val output = apply(source, ProjectSettingsEditor.addInclude(source, ":feature", true))
    assertThat(output).contains("include(\":app\", \":feature\")")
    assertThat(ProjectSettingsEditor.findIncludedPaths(output)).containsExactly(":app", ":feature")
    assertThat(output).contains("// include(\":fake\")")
  }

  @Test fun standaloneIncludeIsRemovedAndCompoundIncludeEntryCanBeRemoved() {
    val standalone = "include(\":app\")\n"
    val output = apply(standalone, ProjectSettingsEditor.removeInclude(standalone, ":app"))
    assertThat(output).isEmpty()

    val compound = "include(\":feature\", \":shared\")\n"
    val compoundOutput = apply(compound, ProjectSettingsEditor.removeInclude(compound, ":feature"))
    assertThat(compoundOutput).isEqualTo("include(\":shared\")\n")
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

  @Test fun multilineKotlinIncludeAddsEntryWithExistingCommaStyle() {
    val source = "include(\n  \":app\",\n  \":profile1\"\n)\n"
    val output = apply(source, ProjectSettingsEditor.addInclude(source, ":profile2", true))
    assertThat(output).isEqualTo("include(\n  \":app\",\n  \":profile1\",\n  \":profile2\"\n)\n")
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

  @Test fun groovySettingsIncludeIsRemovedWithOtherEntriesPreserved() {
    val source = "include ':app', ':feature', ':shared'\n"
    val output = apply(source, ProjectSettingsEditor.removeInclude(source, ":feature"))
    assertThat(output).contains("include ':app', ':shared'")
    assertThat(ProjectSettingsEditor.findIncludedPaths(output)).containsExactly(":app", ":shared")
  }

  @Test fun groovyProjectDirectoryMappingCanBeUpdated() {
    val source = "project(':demo').projectDir = file('../demo')\n"
    val edit = ProjectSettingsEditor.updateProjectDirMapping(source, ":demo", "../new-demo", false)
    assertWithMessage(
        "Groovy mapping edit was not applied.\nParser calls:\n%s",
        parserDiagnostics(source),
    ).that(edit).isInstanceOf(GradleEditResult.Applied::class.java)
    val output = apply(source, edit)
    assertThat(output).contains("file('../new-demo')")
    val mappings = ProjectSettingsEditor.findProjectDirectoryMappings(output)
    assertWithMessage(
        "Groovy mapping was not rediscovered.\nParser calls after edit:\n%s\nOutput:\n%s",
        parserDiagnostics(output),
        output,
    ).that(mappings).containsExactly(ProjectSettingsEditor.ProjectDirectoryMapping(":demo", "'../new-demo'"))
  }

  @Test fun groovyDependencyCanBeFoundAndRenamed() {
    val source = "dependencies {\n  implementation project(':old')\n}\n"
    val dependencies = BuildScriptDependenciesEditor.findProjectDependencies(source)
    assertWithMessage(
        "Groovy dependency was not recognized.\nParser calls:\n%s",
        parserDiagnostics(source),
    ).that(dependencies).containsExactly(
        BuildScriptDependenciesEditor.ProjectDependency("implementation", ":old"),
    )
    val rename = BuildScriptDependenciesEditor.renameProjectDependency(source, "implementation", ":old", ":new")
    assertWithMessage(
        "Groovy dependency rename was not applied.\nParser calls:\n%s",
        parserDiagnostics(source),
    ).that(rename).isInstanceOf(GradleEditResult.Applied::class.java)
    val output = apply(source, rename)
    assertThat(output).contains("project(':new')")
  }

  @Test fun groovyNamedProjectDependencyIsUnsupported() {
    val source = "dependencies { implementation project(path: ':feature') }"
    assertThat(BuildScriptDependenciesEditor.findProjectDependencies(source)).isEmpty()
    assertThat(BuildScriptDependenciesEditor.hasUnsupportedProjectDependencyReference(source, ":feature")).isTrue()
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

  @Test fun fakeDependenciesBlockInsideStringIsIgnored() {
    val source = "val text = \"dependencies { fake }\"\n"
    assertThat(BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", ":feature", true))
        .isInstanceOf(GradleEditResult.Unsupported::class.java)
  }

  @Test fun groovyDependencyIsInsertedIntoAstLocatedBlock() {
    val source = "dependencies {\n}\n"
    val output = apply(source, BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", ":feature", false))
    assertThat(output).contains("implementation project(':feature')")
  }

  @Test fun kotlinDependencyIsInsertedIntoRealBlock() {
    val source = "val text = \"dependencies { fake }\"\ndependencies {\n  implementation(project(\":app\"))\n}\n"
    val output = apply(source, BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", ":feature", true))
    assertThat(output).contains("implementation(project(\":feature\"))")
    assertThat(output).contains("val text = \"dependencies { fake }\"")
  }

  @Test fun kotlinDependencyUsesExistingEntryIndentAndNoBlankGap() {
    val source = "dependencies {\n    implementation(project(\":profile1\"))\n\n}\n"
    val output = apply(source, BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", ":profile2", true))
    assertThat(output).isEqualTo("dependencies {\n    implementation(project(\":profile1\"))\n    implementation(project(\":profile2\"))\n}\n")
  }

  @Test fun kotlinApplicationBuildScriptDependencyBlockIsFound() {
    val source = """plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.app"
    compileSdk = 36
}

dependencies {
    implementation(project(":profile1"))


}
"""
    val output = apply(source, BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", ":profile2", true))
    assertThat(output).contains("implementation(project(\":profile2\"))")
  }

  @Test fun supportedAndUnsupportedDependencyFormsAreDistinguished() {
    val supported = "dependencies { implementation(project(\":feature\")) }"
    val unsupported = "dependencies { implementation(project(path = \":feature\")) }"
    val commented = "// implementation(project(path = \":feature\"))\ndependencies { }"
    val supportedDependencies = BuildScriptDependenciesEditor.findProjectDependencies(supported)
    assertWithMessage(
        "Supported dependency was not recognized.\nParser calls:\n%s",
        parserDiagnostics(supported),
    ).that(supportedDependencies).containsExactly(
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
    val source = "dependencies { implementation(project(\":one\")) }\ndependencies { implementation(project(\":two\")) }\n"
    assertThat(BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", ":feature", true)).isInstanceOf(GradleEditResult.Ambiguous::class.java)
    assertThat(BuildScriptDependenciesEditor.findProjectDependencies(source)).isEmpty()
  }

  @Test fun crlfIsPreserved() {
    val source = "include(\":app\")\r\n"
    val output = apply(source, ProjectSettingsEditor.addInclude(source, ":feature", true))
    assertThat(output).contains("\r\n")
    assertThat(output.replace("\r\n", "")).doesNotContain("\n")
  }

  private fun parserDiagnostics(source: String): String = buildString {
    append("source=").append(source.replace("\n", "\\n")).append('\n')
    for (dsl in GradleDsl.values()) {
      append(dsl.name).append(':')
      append(System.lineSeparator()).append(GradleParser.describeTree(source, dsl))
      GradleParser.parse(source, dsl).forEach { call ->
        append(" ").append(call.name)
            .append('[').append(call.start).append("..").append(call.end).append(']')
            .append(" args=").append(call.arguments)
            .append(" dynamic=").append(call.dynamic)
      }
      append(System.lineSeparator())
    }
  }

  private fun apply(source: String, result: GradleEditResult): String = TextEditApplier.apply(source, checkNotNull(result as? GradleEditResult.Applied).edits)
}