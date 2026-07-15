/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.projects.internal

import androidx.annotation.RestrictTo
import com.android.builder.model.v2.models.ProjectSyncIssues
import com.google.auto.service.AutoService
import com.google.common.collect.ImmutableList
import com.tom.rv2ide.projects.util.RuntimeProbe
import com.tom.rv2ide.eventbus.events.EventReceiver
import com.tom.rv2ide.eventbus.events.editor.DocumentSaveEvent
import com.tom.rv2ide.eventbus.events.file.FileCreationEvent
import com.tom.rv2ide.eventbus.events.file.FileDeletionEvent
import com.tom.rv2ide.eventbus.events.file.FileEvent
import com.tom.rv2ide.eventbus.events.file.FileRenameEvent
import com.tom.rv2ide.eventbus.events.project.ProjectInitializedEvent
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.CachingProject
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.R
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.tasks.executeAsync
import com.tom.rv2ide.tooling.api.IAndroidProject
import com.tom.rv2ide.tooling.api.IProject
import com.tom.rv2ide.tooling.api.messages.result.InitializeResult
import com.tom.rv2ide.tooling.api.models.BuildVariantInfo
import com.tom.rv2ide.utils.DocumentUtils
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.withStopWatch
import com.tom.rv2ide.utils.GradleFileParser
import java.io.File
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.pathString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory

/**
 * Internal implementation of [IProjectManager].
 *
 * @author Akash Yadav
 */
@AutoService(IProjectManager::class)
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
class ProjectManagerImpl : IProjectManager, EventReceiver {

  private var _workspace: WorkspaceImpl? = null
  private var _projectDir: File? = null

  var projectInitialized: Boolean = false
  var cachedInitResult: InitializeResult? = null

  override val projectDir: File
    get() = checkNotNull(_projectDir) { "Cannot get project directory. Path has not been set." }

  override val projectSyncIssues: ProjectSyncIssues?
    get() = getWorkspace()?.getProjectSyncIssues()

  override fun getWorkspace(): IWorkspace? {
    return _workspace
  }

  override fun openProject(directory: File) {
    // IMP: Always use canonical path
    this._projectDir = directory.canonicalFile
  }

  override suspend fun setupProject(project: IProject) {
    this._workspace =
        withStopWatch("Transform project proxy") {
          withContext(Dispatchers.IO) {
            WorkspaceModelBuilder.build(projectDir, CachingProject(project))
          }
        }

    val rootProject = this.getWorkspace() ?: return

    // build variants must be updated before the sources and classpaths are indexed
    updateBuildVariants { buildVariants -> _workspace!!.setVariantSelections(buildVariants) }

    log.info(
        "Found {} project sync issues: {}",
        rootProject.getProjectSyncIssues().syncIssues.size,
        rootProject.getProjectSyncIssues().syncIssues,
    )
    RuntimeProbe.mark("ProjectManager.setupProject:modules=${rootProject.getSubProjects().filterIsInstance<ModuleProject>().size}")

    withStopWatch("Setup project") {
      val indexingDispatcher = Dispatchers.Default.limitedParallelism(4)
      val indexerScope = CoroutineScope(indexingDispatcher)
      val modulesFlow = flow {
        rootProject.getSubProjects().filterIsInstance<ModuleProject>().forEach { emit(it) }
      }
      val jobs =
          modulesFlow.map { module ->
            indexerScope.async {
              if (module.isLazyCompositeBuildModule()) {
                log.info("Setup project: defer indexing lazy composite module {}", module.path)
                return@async
              }
              log.info("Setup project: start indexing module {}", module.path)
              module.indexSourcesAndClasspaths()
              if (module is AndroidModule) {
                module.readResources()
              }
              log.info("Setup project: finished indexing module {}", module.path)
            }
          }


      // wait for the indexing to finish
      jobs.toList().awaitAll()
    }
  }

  private fun shouldPreGenerateAndroidSources(workspace: IWorkspace): Boolean {
    val androidModules = workspace.androidProjects().filterIsInstance<AndroidModule>()
    if (!androidModules.iterator().hasNext()) {
      return false
    }

    return try {
      val allReady = androidModules.all { module ->
        val variant = module.getSelectedVariant() ?: return@all false
        val generatedRoots = variant.mainArtifact.generatedSourceFolders
          .filter { it.exists() && it.isDirectory }
          .filter { path ->
            val normalized = path.absolutePath.replace('\\', '/').lowercase()
            normalized.contains("/build/generated/") || normalized.contains("/build/intermediates/")
          }
        generatedRoots.isNotEmpty()
      }
      !allReady
    } catch (e: Exception) {
      log.warn("Failed to evaluate Android generated source warm-up requirement", e)
      true
    }
  }

  override fun destroy() {
    log.info("Destroying project manager")

    this._workspace?.setVariantSelections(emptyMap())
    this._workspace = null

    this._projectDir = null
    this.cachedInitResult = null
    this.projectInitialized = false
  }
  @JvmOverloads
  fun generateSources(
      builder: BuildService? = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
  ) {
    generateSourcesAsync(builder)
  }

  @JvmOverloads
  fun generateSourcesAsync(
      builder: BuildService? = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE),
      notifyOnSuccess: Boolean = true,
  ): java.util.concurrent.CompletableFuture<Boolean> {
    if (builder == null) {
      log.warn("Cannot generate sources. BuildService is null.")
      return java.util.concurrent.CompletableFuture.completedFuture(false)
    }

    if (!builder.isToolingServerStarted()) {
      flashError(R.string.msg_tooling_server_unavailable)
      return java.util.concurrent.CompletableFuture.completedFuture(false)
    }

    if (builder.isBuildInProgress) {
      log.info("Skipping source generation because a build is already in progress")
      return java.util.concurrent.CompletableFuture.completedFuture(false)
    }

    val tasks =
        getWorkspace()
            ?.androidProjects()
            ?.flatMap { module ->
              val variant = module.getSelectedVariant()
              if (variant == null) {
                log.error("Selected build variant for project '{}' not found", module.path)
                return@flatMap emptyList()
              }

              val mainArtifact = variant.mainArtifact
              val variantNameCapitalized =
                  variant.name.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                  }

              return@flatMap listOf(
                      mainArtifact.resGenTaskName,
                      mainArtifact.sourceGenTaskName,
                      if (module.viewBindingOptions.isEnabled)
                          "dataBindingGenBaseClasses$variantNameCapitalized"
                      else null,
                      "process${variantNameCapitalized}Resources",
                  )
                  .mapNotNull { it?.let { "${module.path}:${it}" } }
            }
            ?.distinct()
            ?.toList() ?: emptyList()

    if (tasks.isEmpty()) {
      log.info("No Android source generation tasks resolved for current workspace")
      return java.util.concurrent.CompletableFuture.completedFuture(false)
    }

    log.info("Generating Android sources before language-server init: {}", tasks)
    return builder.executeTasks(*tasks.toTypedArray()).handle { result, taskErr ->
      if (result == null || !result.isSuccessful || taskErr != null) {
        log.warn("Execution for tasks failed: {} {}", tasks, taskErr ?: "")
        false
      } else {
        log.info("Android source generation completed successfully: {}", tasks)
        if (notifyOnSuccess) {
          notifyProjectUpdate()
        }
        true
      }
    }
  }

  @JvmOverloads
  fun generateSourcesBlocking(
      builder: BuildService? = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE),
      timeoutMs: Long = 120000L,
      notifyOnSuccess: Boolean = true,
  ): Boolean {
    return try {
      generateSourcesAsync(builder, notifyOnSuccess).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
      log.warn("Timed out or failed while waiting for Android source generation", e)
      false
    }
  }


  fun notifyProjectUpdate() {

    executeAsync {
      getWorkspace()?.apply {
        getSubProjects().forEach { subproject ->
          if (subproject is ModuleProject) {
            subproject.indexSources()
          }
        }
      }

      val event = ProjectInitializedEvent()
      event.put(IWorkspace::class.java, getWorkspace())
      EventBus.getDefault().post(event)
    }
  }

  private fun updateBuildVariants(onUpdated: (Map<String, BuildVariantInfo>) -> Unit = {}) {
    val rootProject =
        checkNotNull(this.getWorkspace()) {
          "Cannot update build variants. Root project model is null."
        }

    val buildVariants = mutableMapOf<String, BuildVariantInfo>()
    rootProject.getSubProjects().forEach { subproject ->
      if (subproject is AndroidModule) {

        val variantNames =
            ImmutableList.builder<String>()
                .addAll(subproject.variants.map { variant -> variant.name })
                .build()

        val variantName = subproject.configuredVariant?.name ?: IAndroidProject.DEFAULT_VARIANT

        val moduleDir = File(projectDir, subproject.path.replace(":", File.separator))
        val gradleInfo = GradleFileParser.parseModuleBuildGradle(moduleDir)

        buildVariants[subproject.path] =
            BuildVariantInfo(
                projectPath = subproject.path,
                buildVariants = variantNames,
                selectedVariant = variantName,
                versionName = gradleInfo?.versionName,
                versionCode = gradleInfo?.versionCode,
                minSdk = gradleInfo?.minSdk,
                targetSdk = gradleInfo?.targetSdk,
                compileSdk = gradleInfo?.compileSdk
            )
      }
    }

    onUpdated(buildVariants)
  }
  private fun generateSourcesIfNecessary(event: FileEvent) {
    val builder = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) ?: return
    val file = event.file
    if (getWorkspace()?.isAndroidResource(file) != true) {
      return
    }

    generateSources(builder)
  }


  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.ASYNC)
  fun onFileSaved(event: DocumentSaveEvent) {
    event.file.apply {
      if (isDirectory()) {
        return@apply
      }

      if (extension == "kt" || extension == "kts") {
        getWorkspace()?.findModuleForFile(this, false)?.bumpSourceIndexVersion()
        return@apply
      }

      if (extension != "xml") {
        return@apply
      }

      val module = getWorkspace()?.findModuleForFile(this, false) ?: return@apply
      if (module !is AndroidModule) {
        return@apply
      }

      val isResource =
          module.mainSourceSet?.sourceProvider?.resDirectories?.any {
            this.pathString.contains(it.path)
          } ?: false

      if (isResource) {
        module.updateResourceTable()
      }
    }
  }

  override fun notifyFileCreated(file: File) {
    onFileCreated(FileCreationEvent(file))
  }

  override fun notifyFileDeleted(file: File) {
    onFileDeleted(FileDeletionEvent(file))
  }

  override fun notifyFileRenamed(from: File, to: File) {
    onFileRenamed(FileRenameEvent(from, to))
  }

  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onFileCreated(event: FileCreationEvent) {
    generateSourcesIfNecessary(event)

    if (DocumentUtils.isKotlinFile(event.file.toPath())) {
      getWorkspace()?.findModuleForFile(event.file, false)?.bumpSourceIndexVersion()
    }

    if (DocumentUtils.isJavaFile(event.file.toPath())) {
      getWorkspace()?.findModuleForFile(event.file, false)?.let {
        val sourceRoot = it.findSourceRoot(event.file) ?: return@let

        // add the source node entry
        it.compileJavaSourceClasses.append(event.file.toPath(), sourceRoot)
        it.bumpSourceIndexVersion()
      }
    }
  }

  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onFileDeleted(event: FileDeletionEvent) {
    generateSourcesIfNecessary(event)

    // Remove the source node entry
    // Do not check for Java file DocumentUtils.isJavaFile(...) as it checks for file existence as
    // well. As the file is already deleted, it will always return false
    if (event.file.extension == "kt" || event.file.extension == "kts") {
      getWorkspace()?.findModuleForFile(event.file, false)?.bumpSourceIndexVersion()
    }

    if (event.file.extension == "java") {
      getWorkspace()
          ?.findModuleForFile(event.file, false)
          ?.let { module ->
            module.compileJavaSourceClasses
                .findSource(event.file.toPath())
                ?.let {
                  it.parent?.removeChild(it)
                  module.bumpSourceIndexVersion()
                }
          }
    }
  }

  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onFileRenamed(event: FileRenameEvent) {
    generateSourcesIfNecessary(event)

    // Do not check for Java file DocumentUtils.isJavaFile(...) as it checks for file existence as
    // well. As the file is already renamed to another filename, it will always return false
    if (event.file.extension == "kt" || event.file.extension == "kts") {
      getWorkspace()?.findModuleForFile(event.file, false)?.bumpSourceIndexVersion()
    }

    if (event.file.extension == "java") {
      // remove the source node entry
      getWorkspace()
          ?.findModuleForFile(event.file, false)
          ?.let { module ->
            module.compileJavaSourceClasses
                .findSource(event.file.toPath())
                ?.let {
                  it.parent?.removeChild(it)
                  module.bumpSourceIndexVersion()
                }
          }
    }

    if (DocumentUtils.isKotlinFile(event.newFile.toPath())) {
      getWorkspace()?.findModuleForFile(event.newFile, false)?.bumpSourceIndexVersion()
    }

    if (DocumentUtils.isJavaFile(event.newFile.toPath())) {
      getWorkspace()?.findModuleForFile(event.newFile, false)?.let {
        val sourceRoot = it.findSourceRoot(event.newFile) ?: return@let
        // add the new source node entry
        it.compileJavaSourceClasses.append(event.newFile.toPath(), sourceRoot)
        it.bumpSourceIndexVersion()
      }
    }
  }

  companion object {
    private val log = LoggerFactory.getLogger(ProjectManagerImpl::class.java)

    @JvmStatic
    fun getInstance(): ProjectManagerImpl = IProjectManager.getInstance() as ProjectManagerImpl
  }
}
