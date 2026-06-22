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

import com.tom.rv2ide.projects.GradleProject
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.java.JavaModule
import com.tom.rv2ide.tooling.api.IAndroidProject
import com.tom.rv2ide.tooling.api.IGradleProject
import com.tom.rv2ide.tooling.api.IJavaProject
import com.tom.rv2ide.tooling.api.IProject
import com.tom.rv2ide.tooling.api.ProjectType
import com.tom.rv2ide.tooling.api.models.AndroidProjectMetadata
import com.tom.rv2ide.tooling.api.models.BasicProjectMetadata
import com.tom.rv2ide.tooling.api.models.GradleTask
import com.tom.rv2ide.tooling.api.models.JavaContentRoot
import com.tom.rv2ide.tooling.api.models.JavaModuleCompilerSettings
import com.tom.rv2ide.tooling.api.models.JavaProjectMetadata
import com.tom.rv2ide.tooling.api.models.JavaSourceDirectory
import com.tom.rv2ide.tooling.api.models.params.StringParameter
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Transforms project models from tooling API to the projects API.
 *
 * @author Akash Yadav
 */
internal object WorkspaceModelBuilder {

  private val log = LoggerFactory.getLogger(WorkspaceModelBuilder::class.java)

  fun build(
    projectDir: File,
    project: IProject
  ): WorkspaceImpl? {
    try {
      val allProjects = project.getProjects().get()
      val selectionResult = project.selectProject(StringParameter("")).get()
      check(selectionResult.isSuccessful) {
        "Cannot find root project"
      }

      val rootProject = when (project.getType().get()) {
        ProjectType.Gradle -> transform(project.asGradleProject())
        ProjectType.Android -> transform(project.asAndroidProject())
        else -> throw IllegalStateException(
          "Root project must be either an Android project or a Gradle project"
        )
      }
    val transformedProjects = CopyOnWriteArrayList(transform(allProjects, projectDir))
    if (transformedProjects.none { it.path.startsWith(":buildDeps:") }) {
      augmentWithCompositeBuildDeps(projectDir, transformedProjects)
    }
      log.info(
        "WorkspaceModelBuilder.build workspaceDir={} rootProject={} allProjects={} transformedProjects={}",
        projectDir.canonicalPath,
        rootProject.path,
        allProjects.size,
        transformedProjects.size,
      )
      transformedProjects.take(120).forEach { module ->
        log.info(
          "WorkspaceModelBuilder.project path={} type={} projectDir={} buildDir={}",
          module.path,
          module.javaClass.simpleName,
          module.projectDir.canonicalPath,
          module.buildDir.canonicalPath,
        )
      }
      return WorkspaceImpl(
        projectDir,
        rootProject,
        transformedProjects,
        project.getProjectSyncIssues().get()
      )

    } catch (error: Throwable) {
      log.error("Unable to transform project", error)
      return null
    }
  }

  private fun transform(rootProject: IGradleProject): GradleProject {
    val metadata = rootProject.getMetadata().get()
    return GradleProject(
      name = metadata.name ?: IProject.PROJECT_UNKNOWN,
      description = metadata.description ?: "",
      path = metadata.projectPath,
      projectDir = metadata.projectDir,
      buildDir = metadata.buildDir,
      buildScript = metadata.buildScript,

      // The list will never change, we could make these thread-safe with
      // CopyOnWriteArrayList
      tasks = CopyOnWriteArrayList(rootProject.getTasks().get() ?: listOf()),
    )
  }

  // Workspace-level fallback for composite build dependencies.
  //
  // The tooling/root model currently enumerates only the main build modules, so sources under
  // composite-builds/build-deps/* would otherwise be attributed to the root module ':' and lose
  // their own source index. To keep Java LSP diagnostics/source lookup working for these checked-in
  // dependency sources, synthesize lightweight Java modules from their src/main/java roots.
  private fun augmentWithCompositeBuildDeps(
    workspaceDir: File,
    projects: MutableList<GradleProject>,
  ) {
    val buildDepsDir = File(workspaceDir, "composite-builds/build-deps")
    if (!buildDepsDir.isDirectory) {
      log.info("WorkspaceModelBuilder.compositeBuildDeps skipped missingDir={}", buildDepsDir.path)
      return
    }

    val rootJavaModule = projects.filterIsInstance<JavaModule>().find { it.path == ":" }
    if (rootJavaModule == null) {
      log.warn("WorkspaceModelBuilder.compositeBuildDeps skipped: root Java module not found")
      return
    }

    val existingDirs = projects.map { it.projectDir.canonicalFile }.toHashSet()
    val added = mutableListOf<String>()

    buildDepsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { depDir ->
      val canonicalDepDir = depDir.canonicalFile
      if (existingDirs.contains(canonicalDepDir)) {
        return@forEach
      }

      val mainJavaDir = File(depDir, "src/main/java")
      if (!mainJavaDir.isDirectory) {
        return@forEach
      }

      val contentRoot = JavaContentRoot().apply {
        (sourceDirectories as MutableList).add(JavaSourceDirectory(mainJavaDir, false))
      }
      val pseudoModule = JavaModule(
        name = depDir.name,
        description = "Composite build dependency module",
        path = ":buildDeps:${depDir.name}",
        projectDir = depDir,
        buildDir = File(depDir, "build"),
        buildScript = File(depDir, "build.gradle.kts").takeIf { it.isFile }
          ?: File(depDir, "build.gradle"),
        tasks = emptyList<GradleTask>(),
        compilerSettings = JavaModuleCompilerSettings(
          rootJavaModule.compilerSettings.javaSourceVersion,
          rootJavaModule.compilerSettings.javaBytecodeVersion,
        ),
        contentRoots = listOf(contentRoot),
        dependencies = emptyList(),
        classesJar = null,
        inheritedBootClassPaths = rootJavaModule.inheritedBootClassPaths,
      ).apply {
        markLazyCompositeBuildModule(isHeavyCompositeBuildDep(depDir.name))
      }
      projects.add(pseudoModule)
      existingDirs.add(canonicalDepDir)
      added.add("${pseudoModule.path} -> ${canonicalDepDir.path}")
    }

    log.info(
      "WorkspaceModelBuilder.compositeBuildDeps scannedDir={} addedCount={} added={}",
      buildDepsDir.canonicalPath,
      added.size,
      added,
    )
  }

    private fun isHeavyCompositeBuildDep(moduleName: String): Boolean {
    return moduleName == "jdk-compiler"
      || moduleName == "java-compiler"
      || moduleName == "jdk-jdeps"
      || moduleName == "jaxp"
  }

  private fun transform(
    project: IAndroidProject
  ): AndroidModule {
    val metadata = project.getMetadata().get() as AndroidProjectMetadata
    val libraryMap = project.getLibraryMap().get()
    val variants = project.getVariants().get()
    val configuredVariant = project.getConfiguredVariant().get()
    return AndroidModule(
      name = metadata.name ?: IProject.PROJECT_UNKNOWN,
      description = metadata.description ?: "",
      path = metadata.projectPath,
      projectDir = metadata.projectDir,
      buildDir = metadata.buildDir,
      buildScript = metadata.buildScript,
      tasks = project.getTasks().get(),
      resourcePrefix = metadata.resourcePrefix,
      namespace = metadata.namespace,
      androidTestNamespace = metadata.androidTestNamespace,
      testFixtureNamespace = metadata.testFixtureNamespace,
      projectType = metadata.androidType,
      mainSourceSet = project.getMainSourceSet().get(),
      flags = metadata.flags,
      compilerSettings = metadata.javaCompileOptions,
      viewBindingOptions = metadata.viewBindingOptions,
      bootClassPaths = project.getBootClasspaths().get(),
      libraries = libraryMap.keys,
      libraryMap = libraryMap,
      lintCheckJars = project.getLintCheckJars().get(),
      variants = variants,
      configuredVariant = variants.find { it.name == configuredVariant },
      classesJar = metadata.classesJar
    )
  }

  private fun transform(project: IJavaProject, inheritedBootClassPaths: Collection<File>): JavaModule {
    val metadata = project.getMetadata().get() as JavaProjectMetadata
    return JavaModule(
      name = metadata.name ?: IProject.PROJECT_UNKNOWN,
      description = metadata.description ?: "",
      path = metadata.projectPath,
      projectDir = metadata.projectDir,
      buildDir = metadata.buildDir,
      buildScript = metadata.buildScript,
      tasks = project.getTasks().get(),
      contentRoots = project.getContentRoots().get(),
      dependencies = project.getDependencies().get(),
      compilerSettings = metadata.compilerSettings,
      classesJar = metadata.classesJar,
      inheritedBootClassPaths = inheritedBootClassPaths,
    )
  }

  private fun transform(modules: List<BasicProjectMetadata>, root: IProject): List<GradleProject> {
    val inheritedBootClassPaths = collectAndroidBootClassPaths(modules, root)
    return mutableListOf<GradleProject>().apply {
      for (module in modules) {
        add(createProject(module, root, inheritedBootClassPaths))
      }
    }
  }
  private fun collectAndroidBootClassPaths(
    modules: List<BasicProjectMetadata>,
    root: IProject
  ): List<File> {
    val bootClasspaths = linkedSetOf<File>()
    for (module in modules) {
      val selectionResult = root.selectProject(StringParameter(module.projectPath)).get()
      if (!selectionResult.isSuccessful) {
        continue
      }

      if (root.getType().get() == ProjectType.Android) {
        bootClasspaths.addAll(root.asAndroidProject().getBootClasspaths().get().filter { it.exists() })
      }
    }
    return bootClasspaths.toList()
  }

  private fun createProject(moduleMetadata: BasicProjectMetadata, root: IProject, inheritedBootClassPaths: Collection<File>): GradleProject {

    val selectionResult = root.selectProject(StringParameter(moduleMetadata.projectPath)).get()
    check(selectionResult.isSuccessful) {
      "Selection failed for project '${moduleMetadata.projectPath}' but it is included in all projects."
    }

    val type = root.getType().get() ?: throw java.lang.IllegalStateException("Invalid module data")

    return when (type) {
      ProjectType.Gradle,
      ProjectType.Unknown -> transform(root.asGradleProject())

      ProjectType.Android -> transform(root.asAndroidProject())
      ProjectType.Java -> transform(root.asJavaProject(), inheritedBootClassPaths)
    }
  }
}
