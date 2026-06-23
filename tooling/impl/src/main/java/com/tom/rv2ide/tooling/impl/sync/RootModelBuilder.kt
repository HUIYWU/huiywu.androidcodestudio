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

package com.tom.rv2ide.tooling.impl.sync

import com.tom.rv2ide.builder.model.DefaultProjectSyncIssues
import com.tom.rv2ide.builder.model.DefaultSyncIssue
import com.tom.rv2ide.builder.model.shouldBeIgnored
import com.tom.rv2ide.builder.model.DefaultJavaCompileOptions
import com.tom.rv2ide.tooling.api.IAndroidProject
import com.tom.rv2ide.tooling.api.IGradleProject
import com.tom.rv2ide.tooling.api.IProject
import com.tom.rv2ide.tooling.api.ProjectType
import com.tom.rv2ide.tooling.api.IJavaProject
import com.tom.rv2ide.tooling.api.messages.InitializeProjectParams
import com.tom.rv2ide.tooling.api.models.CompositeBuildDescriptor
import com.tom.rv2ide.tooling.api.models.JavaModuleCompilerSettings
import com.tom.rv2ide.tooling.api.models.JavaProjectMetadata
import com.tom.rv2ide.tooling.api.util.AndroidModulePropertyCopier
import com.tom.rv2ide.tooling.impl.Main
import com.tom.rv2ide.tooling.impl.Main.finalizeLauncher
import com.tom.rv2ide.tooling.impl.internal.ProjectImpl
import java.io.File

import org.gradle.tooling.ConfigurableLauncher
import org.gradle.tooling.model.idea.IdeaProject
import org.slf4j.LoggerFactory

/**
 * Utility class to build the project models.
 *
 * @author Akash Yadav
 */
class RootModelBuilder(initializationParams: InitializeProjectParams) :
    AbstractModelBuilder<RootProjectModelBuilderParams, IProject>(initializationParams) {

  override fun build(param: RootProjectModelBuilderParams): IProject {
 
    val logger = LoggerFactory.getLogger("RootModelBuilder")
    logger.warn("RootModelBuilder.build entered")

    val (projectConnection, cancellationToken) = param

    // do not reference the 'initializationParams' field in the
    val initializationParams = initializationParams

    val executor =
        projectConnection.action { controller ->
          logger.warn("RootModelBuilder: action entered")
          val ideaProject = controller.getModelAndLog(IdeaProject::class.java)
          logger.warn("RootModelBuilder: IdeaProject model loaded")

          val ideaModules = ideaProject.modules

          val modulePaths =
              mapOf(*ideaModules.map { it.name to it.gradleProject.path }.toTypedArray())
          val rootModule =
              ideaModules.find { it.gradleProject.parent == null }
                  ?: throw ModelBuilderException("Unable to find root project")

          val rootProjectVersions = getAndroidVersions(rootModule, controller)

          val syncIssues = hashSetOf<DefaultSyncIssue>()
          val syncIssueReporter = ISyncIssueReporter {
            if (it.shouldBeIgnored()) {
              // this SyncIssue should not be shown to the user
              return@ISyncIssueReporter
            }

            val issue = it as? DefaultSyncIssue ?: AndroidModulePropertyCopier.copy(it)
            syncIssues.add(issue)
          }
          logger.warn("RootModelBuilder: resolving root project model")
          val rootProject =
              if (rootProjectVersions != null) {

                // Root project is an Android project
                checkAgpVersion(rootProjectVersions, syncIssueReporter)
                AndroidProjectModelBuilder(initializationParams)
                    .build(
                        AndroidProjectModelBuilderParams(
                            controller,
                            rootModule,
                            rootProjectVersions,
                            syncIssueReporter,
                        )
                    )
              } else {
                GradleProjectModelBuilder(initializationParams).build(rootModule.gradleProject)
              }
          logger.warn("RootModelBuilder: root project model resolved type={}", rootProject.javaClass.name)
          logger.warn("RootModelBuilder: building module models count={}", ideaModules.size)
          val projects =
              ideaModules.map { ideaModule ->
                logger.warn("RootModelBuilder: building module model name={} path={}", ideaModule.name, ideaModule.gradleProject.path)
                ModuleProjectModelBuilder(initializationParams)
                    .build(
                        ModuleProjectModelBuilderParams(
                            controller,
                            ideaProject,
                            ideaModule,
                            modulePaths,
                            syncIssueReporter,
                        )
                    ).also {
                      logger.warn("RootModelBuilder: built module model name={} type={}", ideaModule.name, it.javaClass.name)
                    }

              }.toMutableList<IGradleProject>()
          logger.warn("RootModelBuilder: base idea modules count={}", projects.size)
          // Keep composite build discovery as metadata-only at this stage.
          // Returning lightweight descriptors avoids mixing new IJavaProject implementations into the
          // existing tooling project list, which previously caused Tooling API compatibility and
          // initialization stability issues. Workspace can consume these descriptors to materialize
          // official module entries while preserving the legacy fallback as a safety net.
          val compositeBuildDescriptors =
              discoverCompositeBuildDeps(rootModule.gradleProject.projectDirectory, projects)
          logger.warn(
              "RootModelBuilder: composite descriptor count={}",
              compositeBuildDescriptors.size,
          )

          return@action ProjectImpl(
              rootProject,
              rootModule.gradleProject.path,
              projects,
              DefaultProjectSyncIssues(syncIssues),
              compositeBuildDescriptors,
          )
        }

    finalizeLauncher(executor)
    applyAndroidModelBuilderProps(executor)

    if (cancellationToken != null) {
      executor.withCancellationToken(cancellationToken)
    }

    logger.warn("Starting build. See build output for more details...")

    val clientRef = Main.client
    if (clientRef != null) {
      clientRef.logOutput("Starting build...")
    }
    return try {
      executor.run().also { logger.debug("Build action executed. Result: {}", it) }
    } catch (err: Throwable) {
      logger.error(
        "RootModelBuilder executor.run failed: type={} message={} causeType={} causeMessage={} rootCauseType={} rootCauseMessage={}",
        err.javaClass.name,
        err.message,
        err.cause?.javaClass?.name,
        err.cause?.message,
        generateSequence(err as Throwable?) { it.cause }.lastOrNull()?.javaClass?.name,
        generateSequence(err as Throwable?) { it.cause }.lastOrNull()?.message,
        err,
      )
      throw err
    }

  }
  private fun discoverCompositeBuildDeps(
    workspaceDir: File,
    projects: List<IGradleProject>,
  ): List<CompositeBuildDescriptor> {
    val logger = LoggerFactory.getLogger("RootModelBuilder")
    val buildDepsDir = File(workspaceDir, "composite-builds/build-deps")
    if (!buildDepsDir.isDirectory) {
      logger.warn("RootModelBuilder composite discovery skipped missingDir={}", buildDepsDir.path)
      return emptyList()
    }

    val existingDirs = projects.map { it.getMetadata().get().projectDir.canonicalFile }.toHashSet()
    val fallbackCompilerSettings = projects
      .filterIsInstance<IJavaProject>()
      .firstOrNull { it.getMetadata().get().projectPath == ":" }
      ?.getMetadata()
      ?.get()
      ?.let { it as? JavaProjectMetadata }
      ?.compilerSettings
      ?: JavaModuleCompilerSettings()
    val discovered = mutableListOf<CompositeBuildDescriptor>()
    buildDepsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { depDir ->
      val canonicalDepDir = depDir.canonicalFile
      if (existingDirs.contains(canonicalDepDir)) {
        return@forEach
      }
      val mainJavaDir = File(depDir, "src/main/java")
      if (!mainJavaDir.isDirectory) {
        return@forEach
      }
      discovered.add(
        CompositeBuildDescriptor(
          name = depDir.name,
          buildName = "buildDeps",
          projectPath = ":buildDeps:${depDir.name}",
          projectDir = canonicalDepDir,
          buildDir = File(depDir, "build"),
          buildScript = File(depDir, "build.gradle.kts").takeIf { it.isFile }
            ?: File(depDir, "build.gradle").takeIf { it.isFile },
          sourceRoots = listOf(mainJavaDir),
          javaSourceVersion = fallbackCompilerSettings.javaSourceVersion,
          javaBytecodeVersion = fallbackCompilerSettings.javaBytecodeVersion,
          isHeavy = isHeavyCompositeBuildDep(depDir.name),
        )
      )
    }

    logger.warn(
      "RootModelBuilder composite discovery scannedDir={} discoveredCount={} discovered={}",
      buildDepsDir.canonicalPath,
      discovered.size,
      discovered.map { "${it.projectPath} -> ${it.projectDir.path}" },
    )
    return discovered
  }

  private fun isHeavyCompositeBuildDep(moduleName: String): Boolean {
    return moduleName == "jdk-compiler"
      || moduleName == "java-compiler"
      || moduleName == "jdk-jdeps"
      || moduleName == "jaxp"
  }


  private fun applyAndroidModelBuilderProps(launcher: ConfigurableLauncher<*>) {
    launcher.addProperty(IAndroidProject.PROPERTY_BUILD_MODEL_ONLY, true)
    launcher.addProperty(IAndroidProject.PROPERTY_INVOKED_FROM_IDE, true)
  }

  private fun ConfigurableLauncher<*>.addProperty(property: String, value: Any) {
    addArguments(String.format("-P%s=%s", property, value))
  }
}
