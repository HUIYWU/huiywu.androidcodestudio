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
import com.tom.rv2ide.builder.model.IJavaCompilerSettings
import com.tom.rv2ide.builder.model.shouldBeIgnored
import com.tom.rv2ide.builder.model.DefaultJavaCompileOptions
import com.tom.rv2ide.tooling.api.IAndroidProject
import com.tom.rv2ide.tooling.api.IGradleProject
import com.tom.rv2ide.tooling.api.IProject
import com.tom.rv2ide.tooling.api.ProjectType
import com.tom.rv2ide.tooling.api.IJavaProject
import com.tom.rv2ide.tooling.api.messages.InitializeProjectParams
import com.tom.rv2ide.tooling.api.messages.LogMessageParams
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
      val buildScript = File(depDir, "build.gradle.kts").takeIf { it.isFile }
        ?: File(depDir, "build.gradle").takeIf { it.isFile }
      val sourceRoots = discoverCompositeSourceRoots(depDir, buildScript)
      if (sourceRoots.isEmpty()) {
        return@forEach
      }
      val sourceRootKinds = sourceRoots.associate { it.canonicalPath to classifyCompositeSourceRoot(it, canonicalDepDir, buildScript) }
      val resolvedCompilerSettings = resolveCompositeCompilerSettings(buildScript, fallbackCompilerSettings)
      val modulePath = ":buildDeps:${depDir.name}"
      discovered.add(
        CompositeBuildDescriptor(
          name = depDir.name,
          buildName = "buildDeps",
          projectPath = ":buildDeps:${depDir.name}",
          projectDir = canonicalDepDir,
          buildDir = File(depDir, "build"),
          buildScript = buildScript,
          sourceRoots = sourceRoots,
          sourceRootKinds = sourceRootKinds,
          classesJar = discoverCompositeClassesJar(depDir, File(depDir, "build")),
          javaSourceVersion = resolvedCompilerSettings.javaSourceVersion,
          javaBytecodeVersion = resolvedCompilerSettings.javaBytecodeVersion,
          isHeavy = isHeavyCompositeBuildDep(depDir.name),
        )
      )
    }

    val discoveredWithClassesJar = discovered.filter { it.classesJar?.isFile == true }
    val sourceRootKindCounts = discovered.flatMap { it.sourceRootKinds.values }.groupingBy { it }.eachCount()
    val sourceRootKindSamples = discovered
      .flatMap { descriptor -> descriptor.sourceRootKinds.entries.map { entry -> "${descriptor.projectPath} -> ${entry.value} -> ${entry.key}" } }
      .take(20)
    val scriptMappedSamples = discovered
      .flatMap { descriptor -> descriptor.sourceRootKinds.entries.map { entry -> "${descriptor.projectPath} -> ${entry.value} -> ${entry.key}" } }
      .filter { it.contains(" -> SCRIPT_MAPPED -> ") }
      .take(20)
    logger.warn(
      "RootModelBuilder composite discovery scannedDir={} discoveredCount={} discovered={}",
      buildDepsDir.canonicalPath,
      discovered.size,
      discovered.map { "${it.projectPath} -> ${it.projectDir.path}" },
    )
    logger.warn(
      "RootModelBuilder composite classesJarSummary count={} hits={}",
      discoveredWithClassesJar.size,
      discoveredWithClassesJar.take(20).map { "${it.projectPath} -> ${it.classesJar?.path}" },
    )
    logger.warn(
      "RootModelBuilder composite sourceRootKindSummary counts={} samples={} scriptMappedSamples={}",
      sourceRootKindCounts,
      sourceRootKindSamples,
      scriptMappedSamples,
    )
    // logback-core temporary debug removed after SCRIPT_MAPPED verification
    return discovered
  }
  private fun resolveCompositeCompilerSettings(
    buildScript: File?,
    fallback: IJavaCompilerSettings,
  ): JavaModuleCompilerSettings {
    val fallbackSettings = JavaModuleCompilerSettings(
      fallback.javaSourceVersion,
      fallback.javaBytecodeVersion,
    )
    val script = buildScript?.takeIf { it.isFile }?.readText() ?: return fallbackSettings
    val source = extractJavaVersion(script, "sourceCompatibility")
      ?: extractToolchainJavaVersion(script)
      ?: extractJvmTargetVersion(script)
      ?: fallback.javaSourceVersion
    val target = extractJavaVersion(script, "targetCompatibility")
      ?: extractToolchainJavaVersion(script)
      ?: extractJvmTargetVersion(script)
      ?: fallback.javaBytecodeVersion
    return JavaModuleCompilerSettings(source, target)
  }

  private fun extractJavaVersion(script: String, propertyName: String): String? {
    fun normalizeVersionToken(raw: String): String = normalizeJavaRelease(raw)

    val patterns = listOf(
      Regex(propertyName + "\\s*(?:=)?\\s*JavaVersion\\.VERSION_([A-Z0-9_]+)"),
      Regex(propertyName + "\\s*(?:=)?\\s*JavaVersion\\.toVersion\\((?:\"([^\"]+)\"|'([^']+)')\\)"),
      Regex(propertyName + "\\s*(?:=)?\\s*(?:\"([^\"]+)\"|'([^']+)')"),
      Regex(propertyName + "\\s*(?:=)?\\s*([0-9]+(?:\\.[0-9]+)?)"),
    )

    patterns.forEach { pattern ->
      val match = pattern.find(script) ?: return@forEach
      val raw = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@forEach
      return normalizeVersionToken(raw)
    }

    return null
  }

  private fun extractToolchainJavaVersion(script: String): String? {
    val patterns = listOf(
      Regex("languageVersion\\s*(?:=)?\\s*JavaLanguageVersion\\.of\\((\\d+)\\)"),
      Regex("languageVersion\\s*(?:=)?\\s*JavaLanguageVersion\\.of\\((?:\"([^\"]+)\"|'([^']+)')\\)"),
    )
    patterns.forEach { pattern ->
      val match = pattern.find(script) ?: return@forEach
      val raw = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@forEach
      return normalizeJavaRelease(raw)
    }
    return null
  }

  private fun extractJvmTargetVersion(script: String): String? {
    val patterns = listOf(
      Regex("jvmTarget\\s*(?:\\.set\\()??\\s*(?:org\\.jetbrains\\.kotlin\\.gradle\\.dsl\\.)?JvmTarget\\.JVM_([0-9_]+)"),
      Regex("jvmTarget\\s*(?:=|\\.set\\()\\s*(?:org\\.jetbrains\\.kotlin\\.gradle\\.dsl\\.)?JvmTarget\\.fromTarget\\((?:\"([^\"]+)\"|'([^']+)')\\)"),
      Regex("jvmTarget\\s*(?:=|\\.set\\()\\s*(?:\"([^\"]+)\"|'([^']+)')"),
    )
    patterns.forEach { pattern ->
      val match = pattern.find(script) ?: return@forEach
      val raw = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@forEach
      return normalizeJavaRelease(raw)
    }
    return null
  }

  private fun normalizeJavaRelease(raw: String): String {
    val cleaned = raw.trim().removeSurrounding("\"").removeSurrounding("'")
    return when (cleaned) {
      "1.8", "1_8", "8", "RELEASE_8" -> "8"
      else -> cleaned
        .removePrefix("RELEASE_")
        .removePrefix("VERSION_")
        .removePrefix("JVM_")
        .removePrefix("1.")
        .removePrefix("1_")
    }
  }

  private fun discoverCompositeClassesJar(depDir: File, buildDir: File): File? {
    val directJar = File(buildDir, "libs/${depDir.name}.jar")
    if (directJar.isFile) {
      return directJar.canonicalFile
    }

    File(buildDir, "libs")
      .listFiles()
      ?.filter { it.isFile && it.extension == "jar" }
      ?.sortedBy { it.name }
      ?.firstOrNull { it.nameWithoutExtension == depDir.name || it.name.startsWith("${depDir.name}-") }
      ?.let { return it.canonicalFile }

    buildDir.walkTopDown()
      .maxDepth(5)
      .filter { it.isFile && it.name == "classes.jar" && it.path.replace('\\', '/').contains("/intermediates/") }
      .firstOrNull()
      ?.let { return it.canonicalFile }

    return null
  }
  private fun discoverCompositeSourceRoots(depDir: File, buildScript: File?): List<File> {

    val roots = linkedSetOf<File>()

    listOf(
      File(depDir, "src/main/java"),
      File(depDir, "src/main/kotlin"),
    ).filterTo(roots) { it.isDirectory }

    val generatedBase = File(depDir, "build/generated")
    if (generatedBase.isDirectory) {
      generatedBase.walkTopDown()
        .maxDepth(4)
        .filter { it.isDirectory }
        .filter {
          val path = it.path.replace('\\', '/')
          path.contains("/build/generated/") &&
            (path.endsWith("/java") || path.endsWith("/kotlin") || path.contains("/ksp/") || path.contains("/kapt/") || path.contains("/annotationProcessor/"))
        }
        .forEach { roots.add(it) }
    }

    roots.addAll(extractCompositeSourceRootsFromScript(depDir, buildScript))
    return roots.toList()
  }
  private fun extractCompositeSourceRootsFromScript(depDir: File, buildScript: File?): List<File> {

    val script = buildScript?.takeIf { it.isFile }?.readText() ?: return emptyList()
    val resolvedVars = mutableMapOf<String, File>()

    val scriptLines = script.lineSequence().toList()
    var lineIndex = 0
    while (lineIndex < scriptLines.size) {
      val line = scriptLines[lineIndex]
      val match = Regex("""^\s*val\s+(\w+)\s*=\s*(.*)$""").find(line)
      if (match != null) {
        val varName = match.groupValues[1]
        val exprParts = mutableListOf<String>()
        val firstExpr = match.groupValues[2].trim()
        if (firstExpr.isNotBlank()) {
          exprParts.add(firstExpr)
        }

        var nextIndex = lineIndex + 1
        while (nextIndex < scriptLines.size) {
          val nextLineRaw = scriptLines[nextIndex]
          val nextLine = nextLineRaw.trim()
          if (nextLine.isBlank()) break
          if (Regex("""^(val\s+\w+\s*=|(?:java|kotlin)\.(?:srcDir|srcDirs|setSrcDirs)\(|if\s*\(|else\b|for\s*\(|while\s*\(|when\s*\(|[A-Za-z_][\w.]*\s*=|//)""").containsMatchIn(nextLine)) {
            break
          }
          exprParts.add(nextLine)
          nextIndex++
        }

        val expr = exprParts.joinToString(" ").trim()
        resolveCompositeSourceRootToken(depDir, expr, resolvedVars)?.let { resolvedVars[varName] = it }
      }
      lineIndex++
    }

    val roots = linkedSetOf<File>()
    Regex("""(?:java|kotlin)\.(?:srcDir|srcDirs|setSrcDirs)\((.*?)\)(?=\R\s*val\s+|\R\s*(?:java|kotlin)\.(?:srcDir|srcDirs|setSrcDirs)\(|\R\s*}|$)""", setOf(RegexOption.DOT_MATCHES_ALL))
      .findAll(script)
      .forEach { match ->
        match.groupValues[1]
          .lineSequence()
          .map { it.trim() }
          .filter { it.isNotBlank() }
          .joinToString(" ")
          .removePrefix("listOf(")
          .removeSuffix(")")
          .split(',')
          .map { it.trim() }
          .filter { it.isNotBlank() }
          .forEach { token ->
            resolveCompositeSourceRootToken(depDir, token, resolvedVars)
              ?.takeIf { it.isDirectory }
              ?.let { roots.add(it) }
          }
      }

    return roots.toList()
  }
  private fun resolveCompositeSourceRootToken(
    depDir: File,
    token: String,
    resolvedVars: Map<String, File>,
  ): File? {
    val normalized = token.trim()
    resolvedVars[normalized]?.let { return it }
    val compositeRootDir = depDir.parentFile?.canonicalFile ?: depDir.canonicalFile

    fun extractSingleArg(pattern: Regex): String? {
      val match = pattern.find(normalized) ?: return null
      return match.groupValues[1].ifBlank { match.groupValues[2] }
    }

    val rootProjectResolve = extractSingleArg(
      Regex("""rootProject\.projectDir\.resolve\((?:\"([^\"]+)\"|'([^']+)')\)""")
    )
    if (!rootProjectResolve.isNullOrBlank()) {
      return compositeRootDir.resolve(rootProjectResolve).canonicalFile
    }

    val rootProjectFile = extractSingleArg(
      Regex("""rootProject\.file\((?:\"([^\"]+)\"|'([^']+)')\)""")
    )
    if (!rootProjectFile.isNullOrBlank()) {
      return compositeRootDir.resolve(rootProjectFile).canonicalFile
    }


    val projectFile = extractSingleArg(
      Regex("""project\.file\((?:\"([^\"]+)\"|'([^']+)')\)""")
    )
    if (!projectFile.isNullOrBlank()) {
      return File(depDir, projectFile).canonicalFile
    }

    val localFile = extractSingleArg(
      Regex("""file\((?:\"([^\"]+)\"|'([^']+)')\)""")
    )
    if (!localFile.isNullOrBlank()) {
      return File(depDir, localFile).canonicalFile
    }

    val directLiteral = Regex("""^(?:\"([^\"]+)\"|'([^']+)')$""").find(normalized)
    val literalPath = directLiteral?.groupValues?.get(1).orEmpty().ifBlank { directLiteral?.groupValues?.get(2).orEmpty() }
    if (literalPath.isNotBlank()) {
      return File(depDir, literalPath).canonicalFile
    }

    return null
  }

  private fun classifyCompositeSourceRoot(sourceRoot: File, projectDir: File, buildScript: File?): String {
    if (isGeneratedCompositeSourceRoot(sourceRoot, projectDir)) {
      return "GENERATED"
    }
    val projectPath = projectDir.canonicalPath.replace('\\', '/') + "/"
    val sourcePath = sourceRoot.canonicalPath.replace('\\', '/')
    if (!sourcePath.startsWith(projectPath)) {
      return "SCRIPT_MAPPED"
    }
    return if (buildScript != null) "LOCAL" else "LOCAL"
  }

  private fun isGeneratedCompositeSourceRoot(sourceRoot: File, projectDir: File): Boolean {
    val normalized = sourceRoot.canonicalPath.replace('\\', '/')
    val projectPath = projectDir.canonicalPath.replace('\\', '/')
    return normalized.startsWith("${projectPath}/build/generated/")
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
