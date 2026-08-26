package com.tom.rv2ide.projects.gradleedit

import com.tom.rv2ide.projects.GradleProject
import com.tom.rv2ide.projects.IWorkspace
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Shared, fail-closed planning entry points for module-management operations.
 *
 * Operations return a plan or blockers; this object deliberately does not modify project files.
 */
object ModuleOperations {
  private val log = LoggerFactory.getLogger(ModuleOperations::class.java)

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

  /** A direct project dependency whose Gradle path will be updated during a module rename. */
  data class DependencyRename(
      val dependentProjectPath: String,
      val buildScript: File,
      val configuration: String,
  )

  /** Rename plan with optional physical directory movement. */
  data class RenamePlan(
      val target: GradleProject,
      val oldGradlePath: String,
      val newGradlePath: String,
      val settingsFile: File,
      val moveDirectory: Boolean,
      val oldDirectory: File,
      val newDirectory: File,
      val renameProjectDirectoryMapping: Boolean,
      val addProjectDirectoryMapping: Boolean,
      val removeProjectDirectoryMapping: Boolean,
      val directoryExpression: String,
      val dependencyRenames: List<DependencyRename>,
  )

  sealed interface RenamePlanResult {
    data class Ready(val plan: RenamePlan) : RenamePlanResult
    data class Blocked(val reasons: List<String>) : RenamePlanResult
  }

sealed interface RenameExecutionResult {
    data class Renamed(val oldGradlePath: String, val newGradlePath: String) : RenameExecutionResult
    data class Failed(val reason: String, val rollbackFailures: List<String> = emptyList()) : RenameExecutionResult
  }

  data class MovePlan(
      val target: GradleProject,
      val gradlePath: String,
      val settingsFile: File,
      val oldDirectory: File,
      val newDirectory: File,
      val updateProjectDirectoryMapping: Boolean,
      val removeProjectDirectoryMapping: Boolean,
      val directoryExpression: String,
  )

  sealed interface MovePlanResult {
    data class Ready(val plan: MovePlan) : MovePlanResult
    data class Blocked(val reasons: List<String>) : MovePlanResult
  }

  sealed interface MoveExecutionResult {
    data class Moved(val gradlePath: String) : MoveExecutionResult
    data class Failed(val reason: String, val rollbackFailures: List<String> = emptyList()) : MoveExecutionResult
  }

   sealed interface DeletionPlanResult {
    data class Ready(val plan: DeletionPlan) : DeletionPlanResult
    data class Blocked(val reasons: List<String>) : DeletionPlanResult
  }

  sealed interface DeletionExecutionResult {
    data class Deleted(val targetProjectPath: String) : DeletionExecutionResult
    data class Failed(val reason: String, val rollbackFailures: List<String> = emptyList()) : DeletionExecutionResult
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
    val settingsDsl = settingsFile.gradleDsl()
    val includeEdit = ProjectSettingsEditor.removeInclude(settingsSource, target.path, settingsDsl)
    val mappingEdit = ProjectSettingsEditor.removeProjectDirMapping(settingsSource, target.path, settingsDsl)
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
      val buildDsl = buildScript.gradleDsl()
      if (BuildScriptDependenciesEditor.hasUnsupportedProjectDependencyReference(source, target.path, buildDsl)) {
        log.warn(
            "Module deletion blocked by unsupported project dependency: dependentProject={}, buildScript={}, {}",
            dependent.path,
            buildScript.absolutePath,
            BuildScriptDependenciesEditor.projectDependencyDiagnostic(source, target.path, buildDsl),
        )
        blockers += "${dependent.path} has an unsupported project dependency reference to ${target.path}"
      }
      BuildScriptDependenciesEditor.findProjectDependencies(source, buildDsl)
          .filter { it.gradlePath == target.path }
          .forEach { dependency ->
            val edit = BuildScriptDependenciesEditor.removeProjectDependency(source, dependency.configuration, target.path, buildDsl)
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

  fun planRename(workspace: IWorkspace, oldGradlePath: String, newGradlePath: String, moveDirectory: Boolean): RenamePlanResult {
    val target = workspace.findProject(oldGradlePath)
        ?: return RenamePlanResult.Blocked(listOf("Module is not present in the synchronized workspace: $oldGradlePath"))
    if (oldGradlePath == ":") return RenamePlanResult.Blocked(listOf("The root Gradle project cannot be renamed"))
    if (!isValidGradlePath(newGradlePath)) return RenamePlanResult.Blocked(listOf("Use a valid Gradle path such as :feature:loader"))
    if (oldGradlePath == newGradlePath) return RenamePlanResult.Blocked(listOf("The new Gradle path is the same as the current path"))
    if (workspace.findProject(newGradlePath) != null) return RenamePlanResult.Blocked(listOf("A module already exists at $newGradlePath"))
    if (!target.buildScript.isFile) return RenamePlanResult.Blocked(listOf("Module build script does not exist: ${target.buildScript.path}"))

    val root = workspace.getProjectDir().canonicalFile
    val settingsFile = findSettingsFile(root) ?: return RenamePlanResult.Blocked(listOf("settings.gradle(.kts) not found in workspace root"))
    val settingsSource = runCatching { settingsFile.readText() }.getOrElse {
      return RenamePlanResult.Blocked(listOf("Could not read settings file: ${it.message}"))
    }
    val settingsDsl = settingsFile.gradleDsl()
    val blockers = mutableListOf<String>()
    val includeEdit = ProjectSettingsEditor.renameInclude(settingsSource, oldGradlePath, newGradlePath, settingsDsl)
    if (includeEdit !is GradleEditResult.Applied) blockers += "Cannot safely rename include for $oldGradlePath: ${includeEdit.reason()}"

    val allMappings = ProjectSettingsEditor.findProjectDirectoryMappings(settingsSource, settingsDsl)
    val mappings = allMappings.filter { it.gradlePath == oldGradlePath }
    if (allMappings.any { it.gradlePath == newGradlePath }) {
      blockers += "A projectDir mapping already exists for $newGradlePath"
    }
    if (mappings.size > 1) blockers += "Multiple projectDir mappings found for $oldGradlePath"
    val oldDirectory = target.projectDir.canonicalFile
    val newDirectory = File(root, newGradlePath.removePrefix(":").replace(':', File.separatorChar)).canonicalFile
    var renameMapping = false
    var addMapping = false
    var removeMapping = false
    var directoryExpression = ""
    if (moveDirectory) {
      if (!oldDirectory.isDirectory) blockers += "Module directory does not exist: ${oldDirectory.path}"
      if (!oldDirectory.toPath().startsWith(root.toPath())) {
        blockers += "Moving modules outside the workspace root is not supported: ${oldDirectory.path}"
      }
      if (newDirectory.exists()) blockers += "Target module directory already exists: ${newDirectory.path}"
      if (newDirectory.toPath().startsWith(oldDirectory.toPath())) {
        blockers += "Target module directory cannot be inside the current module directory: ${newDirectory.path}"
      }
      removeMapping = mappings.size == 1
      if (removeMapping) {
        val mappingEdit = ProjectSettingsEditor.removeProjectDirMapping(settingsSource, oldGradlePath, settingsDsl)
        if (mappingEdit !is GradleEditResult.Applied) blockers += "Cannot safely remove projectDir mapping for $oldGradlePath: ${mappingEdit.reason()}"
      }
    } else {
      if (oldDirectory.toPath().startsWith(root.toPath())) {
        directoryExpression = root.toPath().relativize(oldDirectory.toPath()).toString().replace(File.separatorChar, '/')
        addMapping = mappings.isEmpty()
        if (mappings.size == 1) {
          val mappedDirectory = resolveProjectDirectory(root, mappings.single().directoryExpression)
          if (mappedDirectory == newDirectory && mappedDirectory == oldDirectory) {
            // The mapping is redundant after the rename: the new Gradle path matches the default directory layout.
            removeMapping = true
            val mappingEdit = ProjectSettingsEditor.removeProjectDirMapping(settingsSource, oldGradlePath, settingsDsl)
            if (mappingEdit !is GradleEditResult.Applied) blockers += "Cannot safely remove projectDir mapping for $oldGradlePath: ${mappingEdit.reason()}"
          } else {
            renameMapping = true
            val mappingEdit = ProjectSettingsEditor.renameProjectDirMappingPath(settingsSource, oldGradlePath, newGradlePath, settingsDsl)
            if (mappingEdit !is GradleEditResult.Applied) blockers += "Cannot safely rename projectDir mapping for $oldGradlePath: ${mappingEdit.reason()}"
          }
        }
      } else if (mappings.size != 1) {
        blockers += "Module directory is outside the workspace and has no safe projectDir mapping: ${oldDirectory.path}"
      } else {
        renameMapping = true
      }
    }

    val renames = mutableListOf<DependencyRename>()
    workspaceProjects(workspace).filter { it.path != oldGradlePath }.forEach { dependent ->
      if (dependent.projectDir.canonicalFile.toPath().startsWith(oldDirectory.toPath())) {
        blockers += "Cannot rename a module directory containing another synchronized project: ${dependent.path}"
        return@forEach
      }
      val buildScript = dependent.buildScript
      if (!buildScript.isFile) {
        blockers += "Cannot inspect build script for ${dependent.path}: ${buildScript.path}"
        return@forEach
      }
      val source = runCatching { buildScript.readText() }.getOrElse {
        blockers += "Cannot read build script for ${dependent.path}: ${it.message}"
        return@forEach
      }
      val buildDsl = buildScript.gradleDsl()
      if (BuildScriptDependenciesEditor.hasUnsupportedProjectDependencyReference(source, oldGradlePath, buildDsl)) {
        blockers += "${dependent.path} has an unsupported project dependency reference to $oldGradlePath"
      }
      BuildScriptDependenciesEditor.findProjectDependencies(source, buildDsl)
          .filter { it.gradlePath == oldGradlePath }
          .forEach { dependency ->
            val edit = BuildScriptDependenciesEditor.renameProjectDependency(source, dependency.configuration, oldGradlePath, newGradlePath, buildDsl)
            if (edit is GradleEditResult.Applied) renames += DependencyRename(dependent.path, buildScript, dependency.configuration)
            else blockers += "Cannot safely rename ${dependency.configuration} dependency in ${dependent.path}: ${edit.reason()}"
          }
    }
    if (blockers.isNotEmpty()) return RenamePlanResult.Blocked(blockers.distinct())
    return RenamePlanResult.Ready(
        RenamePlan(
            target = target,
            oldGradlePath = oldGradlePath,
            newGradlePath = newGradlePath,
            settingsFile = settingsFile,
            moveDirectory = moveDirectory,
            oldDirectory = oldDirectory,
            newDirectory = newDirectory,
            renameProjectDirectoryMapping = renameMapping,
            addProjectDirectoryMapping = addMapping,
            removeProjectDirectoryMapping = removeMapping,
            directoryExpression = directoryExpression,
            dependencyRenames = renames.distinctBy {
              Triple(it.buildScript.canonicalPath, it.configuration, it.dependentProjectPath)
            },
        ),
    )
  }

  fun planMove(workspace: IWorkspace, gradlePath: String, newDirectory: File): MovePlanResult {
    val target = workspace.findProject(gradlePath)
        ?: return MovePlanResult.Blocked(listOf("Module is not present in the synchronized workspace: $gradlePath"))
    if (gradlePath == ":") return MovePlanResult.Blocked(listOf("The root Gradle project cannot be moved"))
    val root = workspace.getProjectDir().canonicalFile
    val oldDirectory = target.projectDir.canonicalFile
    val destination = newDirectory.canonicalFile
    val blockers = mutableListOf<String>()
    if (!target.buildScript.isFile) blockers += "Module build script does not exist: ${target.buildScript.path}"
    if (!oldDirectory.isDirectory) blockers += "Module directory does not exist: ${oldDirectory.path}"
    if (!oldDirectory.toPath().startsWith(root.toPath())) blockers += "Moving modules outside the workspace root is not supported: ${oldDirectory.path}"
    if (!destination.toPath().startsWith(root.toPath())) blockers += "Target module directory must be inside the workspace root: ${destination.path}"
    if (destination.exists()) blockers += "Target module directory already exists: ${destination.path}"
    if (destination.toPath().startsWith(oldDirectory.toPath())) blockers += "Target module directory cannot be inside the current module directory: ${destination.path}"
    workspaceProjects(workspace).filter { it.path != gradlePath }.forEach { project ->
      if (project.projectDir.canonicalFile.toPath().startsWith(oldDirectory.toPath())) {
        blockers += "Cannot move a module directory containing another synchronized project: ${project.path}"
      }
    }
    val settingsFile = findSettingsFile(root) ?: blockers.add("settings.gradle(.kts) not found in workspace root").let { return MovePlanResult.Blocked(blockers) }
    val source = runCatching { settingsFile.readText() }.getOrElse {
      return MovePlanResult.Blocked(listOf("Could not read settings file: ${it.message}"))
    }
    val dsl = settingsFile.gradleDsl()
    val mappings = ProjectSettingsEditor.findProjectDirectoryMappings(source, dsl).filter { it.gradlePath == gradlePath }
    if (mappings.size > 1) blockers += "Multiple projectDir mappings found for $gradlePath"
    val mapping = mappings.singleOrNull()
    val expression = root.toPath().relativize(destination.toPath()).toString().replace(File.separatorChar, '/')
    val defaultDirectory = File(
        root,
        gradlePath.removePrefix(":").replace(':', File.separatorChar),
    ).canonicalFile
    // A mapping is redundant when the moved directory matches Gradle's default path layout.
    val remove = mapping != null && destination == defaultDirectory
    val update = mapping != null && !remove
    if (mapping != null && !remove) {
      val edit = ProjectSettingsEditor.updateProjectDirMapping(source, gradlePath, expression, dsl)
      if (edit !is GradleEditResult.Applied) blockers += "Cannot safely update projectDir mapping for $gradlePath: ${edit.reason()}"
    }
    if (mapping == null) {
      val edit = ProjectSettingsEditor.addProjectDirMapping(source, gradlePath, expression, dsl)
      if (edit !is GradleEditResult.Applied) blockers += "Cannot safely add projectDir mapping for $gradlePath: ${edit.reason()}"
    }
    if (blockers.isNotEmpty()) return MovePlanResult.Blocked(blockers.distinct())
    return MovePlanResult.Ready(MovePlan(target, gradlePath, settingsFile, oldDirectory, destination, update, remove, expression))
  }

  fun executeMove(plan: MovePlan): MoveExecutionResult {
    val root = plan.settingsFile.parentFile.canonicalFile
    val transaction = runCatching { ProjectEditTransaction.begin(root) }
        .getOrElse { return MoveExecutionResult.Failed(it.message ?: "Could not start project edit transaction") }
    return try {
      transaction.moveDirectory(plan.oldDirectory, plan.newDirectory)
      val source = plan.settingsFile.readText()
      val dsl = plan.settingsFile.gradleDsl()
      val edit = when {
        plan.removeProjectDirectoryMapping -> ProjectSettingsEditor.removeProjectDirMapping(source, plan.gradlePath, dsl)
        plan.updateProjectDirectoryMapping -> ProjectSettingsEditor.updateProjectDirMapping(source, plan.gradlePath, plan.directoryExpression, dsl)
        else -> ProjectSettingsEditor.addProjectDirMapping(source, plan.gradlePath, plan.directoryExpression, dsl)
      }
      transaction.applyRequiredTextEdit(plan.settingsFile, source, edit, "projectDir mapping")
      transaction.commit()
      MoveExecutionResult.Moved(plan.gradlePath)
    } catch (error: Throwable) {
      MoveExecutionResult.Failed(error.message ?: "Module move failed", transaction.rollback().mapNotNull { it.message })
    }
  }

  /** Executes a rename plan, optionally including the physical directory move. */
  fun executeRename(plan: RenamePlan): RenameExecutionResult {
    val root = plan.settingsFile.parentFile.canonicalFile
    val transaction = runCatching {
      ProjectEditTransaction.begin(root, plan.dependencyRenames.map { it.buildScript.parentFile })
    }.getOrElse { return RenameExecutionResult.Failed(it.message ?: "Could not start project edit transaction") }
    return try {
      if (plan.moveDirectory) {
        transaction.moveDirectory(plan.oldDirectory, plan.newDirectory)
      }

      val settingsDsl = plan.settingsFile.gradleDsl()
      val settingsSource = plan.settingsFile.readText()
      transaction.applyRequiredTextEdit(
          plan.settingsFile,
          settingsSource,
          ProjectSettingsEditor.renameInclude(settingsSource, plan.oldGradlePath, plan.newGradlePath, settingsDsl),
          "settings include",
      )
      val afterInclude = plan.settingsFile.readText()
      when {
        plan.renameProjectDirectoryMapping -> transaction.applyRequiredTextEdit(
            plan.settingsFile,
            afterInclude,
            ProjectSettingsEditor.renameProjectDirMappingPath(afterInclude, plan.oldGradlePath, plan.newGradlePath, settingsDsl),
            "projectDir mapping",
        )
        plan.addProjectDirectoryMapping -> transaction.applyRequiredTextEdit(
            plan.settingsFile,
            afterInclude,
            ProjectSettingsEditor.addProjectDirMapping(afterInclude, plan.newGradlePath, plan.directoryExpression, settingsDsl),
            "projectDir mapping",
        )
        plan.removeProjectDirectoryMapping -> transaction.applyRequiredTextEdit(
            plan.settingsFile,
            afterInclude,
            ProjectSettingsEditor.removeProjectDirMapping(afterInclude, plan.oldGradlePath, settingsDsl),
            "projectDir mapping",
        )
      }
      plan.dependencyRenames.groupBy { it.buildScript.canonicalFile }.forEach { (buildScript, renames) ->
        renames.forEach { rename ->
          val source = buildScript.readText()
          transaction.applyRequiredTextEdit(
              buildScript,
              source,
              BuildScriptDependenciesEditor.renameProjectDependency(
                  source,
                  rename.configuration,
                  plan.oldGradlePath,
                  plan.newGradlePath,
                  buildScript.gradleDsl(),
              ),
              "dependency in ${rename.dependentProjectPath}",
          )
        }
      }
      transaction.commit()
      RenameExecutionResult.Renamed(plan.oldGradlePath, plan.newGradlePath)
    } catch (error: Throwable) {
      RenameExecutionResult.Failed(error.message ?: "Module rename failed", transaction.rollback().mapNotNull { it.message })
    }
  }

  /** Executes a previously approved plan. Call [planDeletion] again immediately before confirmation. */
  fun executeDeletion(plan: DeletionPlan): DeletionExecutionResult {
    val root = plan.settingsFile.parentFile.canonicalFile
    val targetDirectory = plan.target.projectDir.canonicalFile
    if (!targetDirectory.exists() || !targetDirectory.isDirectory) {
      return DeletionExecutionResult.Failed("Module directory no longer exists: ${targetDirectory.path}")
    }
    val stagedDirectory = File(
        targetDirectory.parentFile,
        ".${targetDirectory.name}.androidide-delete-${UUID.randomUUID()}",
    )
    val transaction = runCatching {
      ProjectEditTransaction.begin(root, plan.dependencyRemovals.map { it.buildScript.parentFile })
    }.getOrElse {
      return DeletionExecutionResult.Failed(it.message ?: "Could not start project edit transaction")
    }
    var staged = false
    return try {
      // Move first so the directory can be restored if any following Gradle edit fails.
      moveDirectory(targetDirectory, stagedDirectory)
      staged = true

      if (plan.removeProjectDirectoryMapping) {
        editSettings(plan, transaction) { source ->
          ProjectSettingsEditor.removeProjectDirMapping(source, plan.target.path, plan.settingsFile.gradleDsl())
        }
      }
      if (plan.removeSettingsInclude) {
        editSettings(plan, transaction) { source ->
          ProjectSettingsEditor.removeInclude(source, plan.target.path, plan.settingsFile.gradleDsl())
        }
      }
      plan.dependencyRemovals.groupBy { it.buildScript.canonicalFile }.forEach { (buildScript, removals) ->
        removals.forEach { removal ->
          val source = buildScript.readText()
          transaction.applyTextEdit(
              buildScript,
              source,
              BuildScriptDependenciesEditor.removeProjectDependency(source, removal.configuration, removal.targetProjectPath, buildScript.gradleDsl()),
              "dependency in ${removal.dependentProjectPath}",
          )
        }
      }
      if (!stagedDirectory.deleteRecursively()) throw IOException("Could not permanently remove module directory: ${targetDirectory.path}")
      transaction.commit()
      DeletionExecutionResult.Deleted(plan.target.path)
    } catch (error: Throwable) {
      val rollbackFailures = transaction.rollback().mapNotNull { it.message }.toMutableList()
      if (staged && stagedDirectory.exists()) {
        runCatching { moveDirectory(stagedDirectory, targetDirectory) }
            .exceptionOrNull()?.message?.let(rollbackFailures::add)
      }
      DeletionExecutionResult.Failed(error.message ?: "Module deletion failed", rollbackFailures)
    }
  }

  private fun moveDirectory(from: File, to: File) {
    try {
      Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
      Files.move(from.toPath(), to.toPath())
    }
  }

  private fun editSettings(
      plan: DeletionPlan,
      transaction: ProjectEditTransaction,
      operation: (String) -> GradleEditResult,
  ) {
    val source = plan.settingsFile.readText()
    transaction.applyTextEdit(plan.settingsFile, source, operation(source), "settings file")
  }

  /**
   * Returns concrete projects only. Gradle may expose implicit parent projects such as :exa for
   * a real nested module :exa:inj; those parents have no build script of their own and must not be
   * inspected or edited as dependent projects.
   */
  private fun workspaceProjects(workspace: IWorkspace): List<GradleProject> =
      (listOf(workspace.getRootProject()) + workspace.getSubProjects())
          .distinctBy { it.path }
          .filter { it.path == ":" || it.buildScript.isFile }

  /** Resolves a literal projectDir mapping expression without interpreting arbitrary code. */
  private fun resolveProjectDirectory(root: File, expression: String): File {
    val literal = expression.trim().let {
      if (it.length >= 2 && ((it.first() == '"' && it.last() == '"') || (it.first() == '\'' && it.last() == '\''))) {
        it.substring(1, it.length - 1)
      } else {
        it
      }
    }
    val file = File(literal)
    return if (file.isAbsolute) file.canonicalFile else File(root, literal).canonicalFile
  }

  private fun findSettingsFile(root: File): File? =
      listOf(File(root, "settings.gradle.kts"), File(root, "settings.gradle")).firstOrNull { it.isFile }
  private fun File.gradleDsl(): GradleDsl = if (name.endsWith(".gradle.kts")) GradleDsl.KOTLIN else GradleDsl.GROOVY
  private fun isValidGradlePath(path: String) = path.matches(Regex(":([A-Za-z][A-Za-z0-9_-]*)(:[A-Za-z][A-Za-z0-9_-]*)*"))
  private fun GradleEditResult.isUnsafe() = this is GradleEditResult.Unsupported || this is GradleEditResult.Ambiguous || this is GradleEditResult.Invalid
  private fun GradleEditResult.reason(): String = when (this) {
    is GradleEditResult.Unsupported -> reason
    is GradleEditResult.Ambiguous -> reason
    is GradleEditResult.Invalid -> reason
    GradleEditResult.NoChange -> "target was not found"
    is GradleEditResult.Applied -> "unexpectedly already applicable"
  }
}