package com.tom.rv2ide.projects.gradleedit

import com.tom.rv2ide.projects.GradleProject
import com.tom.rv2ide.projects.IWorkspace
import java.io.File

/**
 * Shared, fail-closed planning entry points for module-management operations.
 *
 * Operations return a plan or blockers; this object deliberately does not modify project files.
 */
object ModuleOperations {
  data class DependencyRemoval(
      val dependentProjectPath: String,
      val buildScript: File,
      val configuration: String,
      val targetProjectPath: String,
  )

  data class DeletionPlan(
      val target: GradleProject,
      val settingsFile: File,
      val removeSettingsInclude: Boolean,
      val removeProjectDirectoryMapping: Boolean,
      val dependencyRemovals: List<DependencyRemoval>,
  )

  sealed interface DeletionPlanResult {
    data class Ready(val plan: DeletionPlan) : DeletionPlanResult
    data class Blocked(val reasons: List<String>) : DeletionPlanResult
  }

  fun planDeletion(workspace: IWorkspace, targetProjectPath: String): DeletionPlanResult {
    val target = workspace.findProject(targetProjectPath)
        ?: return DeletionPlanResult.Blocked(listOf("Module is not present in the synchronized workspace: $targetProjectPath"))
    if (target.path == ":") return DeletionPlanResult.Blocked(listOf("The root Gradle project cannot be deleted"))
    if (!target.projectDir.isDirectory) return DeletionPlanResult.Blocked(listOf("Module directory does not exist: ${target.projectDir.path}"))
    if (!target.buildScript.isFile) return DeletionPlanResult.Blocked(listOf("Module build script does not exist: ${target.buildScript.path}"))

    val root = workspace.getProjectDir().canonicalFile
    val targetDir = target.projectDir.canonicalFile
    if (!targetDir.toPath().startsWith(root.toPath())) {
      return DeletionPlanResult.Blocked(listOf("Deleting modules outside the workspace root is not supported: ${targetDir.path}"))
    }

    val settingsFile = findSettingsFile(root) ?: return DeletionPlanResult.Blocked(listOf("settings.gradle(.kts) not found in workspace root"))
    val settingsSource = runCatching { settingsFile.readText() }.getOrElse {
      return DeletionPlanResult.Blocked(listOf("Could not read settings file: ${it.message}"))
    }
    val includeEdit = ProjectSettingsEditor.removeInclude(settingsSource, target.path)
    val mappingEdit = ProjectSettingsEditor.removeProjectDirMapping(settingsSource, target.path)
    val blockers = mutableListOf<String>()
    if (includeEdit.isUnsafe()) blockers += "Cannot safely remove include for ${target.path}: ${includeEdit.reason()}"
    if (mappingEdit.isUnsafe()) blockers += "Cannot safely remove projectDir mapping for ${target.path}: ${mappingEdit.reason()}"

    val removals = mutableListOf<DependencyRemoval>()
    workspaceProjects(workspace).filter { it.path != target.path }.forEach { dependent ->
      val buildScript = dependent.buildScript
      if (!buildScript.isFile) {
        blockers += "Cannot inspect build script for ${dependent.path}: ${buildScript.path}"
        return@forEach
      }
      val source = runCatching { buildScript.readText() }.getOrElse {
        blockers += "Cannot read build script for ${dependent.path}: ${it.message}"
        return@forEach
      }
      if (BuildScriptDependenciesEditor.hasUnsupportedProjectDependencyReference(source, target.path)) {
        blockers += "${dependent.path} has an unsupported project dependency reference to ${target.path}"
      }
      BuildScriptDependenciesEditor.findProjectDependencies(source)
          .filter { it.gradlePath == target.path }
          .forEach { dependency ->
            val edit = BuildScriptDependenciesEditor.removeProjectDependency(source, dependency.configuration, target.path)
            if (edit is GradleEditResult.Applied) {
              removals += DependencyRemoval(dependent.path, buildScript, dependency.configuration, target.path)
            } else {
              blockers += "Cannot safely remove ${dependency.configuration} dependency from ${dependent.path}: ${edit.reason()}"
            }
          }
    }
    if (blockers.isNotEmpty()) return DeletionPlanResult.Blocked(blockers.distinct())
    return DeletionPlanResult.Ready(
        DeletionPlan(
            target, settingsFile, includeEdit is GradleEditResult.Applied, mappingEdit is GradleEditResult.Applied,
            removals.distinctBy { Triple(it.buildScript.canonicalPath, it.configuration, it.targetProjectPath) },
        ),
    )
  }

  private fun workspaceProjects(workspace: IWorkspace): List<GradleProject> =
      (listOf(workspace.getRootProject()) + workspace.getSubProjects()).distinctBy { it.path }
  private fun findSettingsFile(root: File): File? =
      listOf(File(root, "settings.gradle.kts"), File(root, "settings.gradle")).firstOrNull { it.isFile }
  private fun GradleEditResult.isUnsafe() = this is GradleEditResult.Unsupported || this is GradleEditResult.Ambiguous || this is GradleEditResult.Invalid
  private fun GradleEditResult.reason(): String = when (this) {
    is GradleEditResult.Unsupported -> reason
    is GradleEditResult.Ambiguous -> reason
    is GradleEditResult.Invalid -> reason
    GradleEditResult.NoChange -> "target was not found"
    is GradleEditResult.Applied -> "unexpectedly already applicable"
  }
}