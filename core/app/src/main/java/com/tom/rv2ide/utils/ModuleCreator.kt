/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.utils

import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.projects.gradleedit.BuildScriptDependenciesEditor
import com.tom.rv2ide.projects.gradleedit.GradleDsl as EditorGradleDsl
import com.tom.rv2ide.projects.gradleedit.GradleEditResult
import com.tom.rv2ide.projects.gradleedit.ProjectEditTransaction
import com.tom.rv2ide.projects.gradleedit.ProjectSettingsEditor
import com.tom.rv2ide.tooling.api.models.ModuleCreationKind
import com.tom.rv2ide.tooling.api.models.ModuleCreationValidation
import com.tom.rv2ide.tooling.api.models.ModuleCreationValidationRequest
import com.tom.rv2ide.tooling.api.models.ModuleSourceLanguage
import com.tom.rv2ide.tooling.api.models.ProjectCreationCapabilities
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/** Creates modules from one request shared by validation, preview, and execution. */
class ModuleCreator {
  private val log = LoggerFactory.getLogger(ModuleCreator::class.java)

  data class CreationResult(val success: Boolean, val errorMessage: String? = null)

  fun getProjectCreationCapabilities(timeoutSeconds: Long = 30): ProjectCreationCapabilities? {
    val service = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) ?: return null
    if (!service.isToolingServerStarted() || service.isBuildInProgress) return null
    val future = runCatching { service.getProjectCreationCapabilities() }.getOrNull() ?: return null
    return runCatching { future.get(timeoutSeconds, TimeUnit.SECONDS) }.onFailure {
      log.warn("Failed to read project creation capabilities", it)
      runCatching { service.cancelCurrentBuild() }
    }.getOrNull()
  }

  /** Returns null when the live Gradle probe is unavailable; callers must fail closed. */
  private fun validateModuleCreation(
      request: ModuleCreationRequest,
      buildScript: String,
  ): ModuleCreationValidation? {
    val service = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) ?: return null
    if (!service.isToolingServerStarted() || service.isBuildInProgress) return null
    val probe = ModuleCreationValidationRequest(
        modulePath = request.gradlePath,
        buildFileName = request.moduleBuildFileName,
        buildScript = buildScript,
    )
    return runCatching { service.validateModuleCreation(probe).get() }
        .onFailure { log.warn("Module creation probe failed; path={}", request.gradlePath, it) }
        .getOrNull()
  }

  fun createModule(request: ModuleCreationRequest): CreationResult {
    val effective = request.withDetectedAndroidConfig()
    val local = preflightLocal(effective)
    if (!local.success) return local
    val buildScript = generateBuildScript(effective, packageName(effective))
    val validation = validateModuleCreation(effective, buildScript)
        ?: return CreationResult(false, "Module creation validation is unavailable. Wait for Gradle synchronization to finish and try again.")
    if (!validation.isValid) {
      return CreationResult(false, validation.message ?: "This module configuration cannot be applied to the current Gradle project.")
    }
    return writeValidatedModule(effective, buildScript)
  }

  private fun preflightLocal(request: ModuleCreationRequest): CreationResult {
    if (ModuleCreationRequest.normalizePath(request.gradlePath) != request.gradlePath) {
      return CreationResult(false, "Use a valid Gradle path such as :feature:profile")
    }
    if (!request.projectRoot.isDirectory) return CreationResult(false, "Project root directory does not exist")
    val moduleDirectory = request.moduleDirectory.canonicalFile
    if (!moduleDirectory.toPath().startsWith(request.projectRoot.canonicalFile.toPath()) || moduleDirectory == request.projectRoot.canonicalFile) {
      return CreationResult(false, "Module directory must be inside the project root")
    }
    if (request.overrideModuleDirectory == null && moduleDirectory.exists()) return CreationResult(false, "Module '${request.gradlePath}' already exists")
    runCatching { findSettingsFile(request.projectRoot) }.getOrElse {
      return CreationResult(false, it.message ?: "settings.gradle(.kts) not found")
    }
    return CreationResult(true)
  }

  private fun writeValidatedModule(request: ModuleCreationRequest, buildScript: String): CreationResult {
    if (request.overrideModuleDirectory == null && request.moduleDirectory.exists()) {
      return CreationResult(false, "Module '${request.gradlePath}' already exists")
    }
    val settings = runCatching { findSettingsFile(request.projectRoot) }.getOrElse {
      return CreationResult(false, it.message ?: "settings.gradle(.kts) not found")
    }
    val consumer = request.applicationProject?.let { File(it.buildFile) }
    val transaction = runCatching {
      ProjectEditTransaction.begin(request.projectRoot, listOfNotNull(consumer?.parentFile)).also { edit ->
        edit.capture(settings)
        consumer?.let(edit::capture)
        if (request.moduleDirectory.exists()) {
          generatedModuleFiles(request).forEach { file ->
            if (file.isFile) edit.capture(file) else edit.trackCreatedFile(file)
          }
        } else {
          missingParentDirectories(request.projectRoot, request.moduleDirectory).forEach(edit::trackCreatedParentDirectory)
          edit.trackCreatedDirectory(request.moduleDirectory)
        }
      }
    }.getOrElse { return CreationResult(false, it.message ?: "Could not prepare project edit transaction") }

    return try {
      writeModule(request, buildScript)

      applyEdit(settings, settings.readText(), ProjectSettingsEditor.addInclude(settings.readText(), request.gradlePath, editorDsl(settings)), "settings file", transaction)
      request.overrideModuleDirectory?.let { directory ->
        val source = settings.readText()
        val relativeDirectory = directory.canonicalFile.relativeTo(request.projectRoot.canonicalFile).invariantSeparatorsPath
        applyEdit(settings, source, ProjectSettingsEditor.addProjectDirMapping(source, request.gradlePath, relativeDirectory, editorDsl(settings)), "settings project directory", transaction)
      }
      consumer?.let { file ->
        val source = file.readText()
        applyEdit(file, source, BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", request.gradlePath, editorDsl(file)), "consumer build script", transaction)
      }
      transaction.commit()
      CreationResult(true)
    } catch (error: Exception) {
      transaction.rollback().forEach { log.warn("Module creation rollback failed", it) }
      CreationResult(false, error.message ?: "Unknown error occurred")
    }
  }

  private fun ModuleCreationRequest.withDetectedAndroidConfig(): ModuleCreationRequest {
    val applicationSource = applicationProject
        ?.let { runCatching { File(it.buildFile).readText() }.getOrNull() }
        .orEmpty()
    val detectedJavaVersion = javaVersion
        ?: detectJavaVersion(applicationSource)
        ?: runtimeJavaVersion()
        ?: 8
    if (kind != ModuleCreationKind.ANDROID_LIBRARY) {
      return copy(javaVersion = detectedJavaVersion)
    }
    val sdk = Regex("compileSdk\\s*[=:]\\s*(\\d+)")
        .find(applicationSource)?.groupValues?.get(1)?.toIntOrNull() ?: compileSdk
    val min = Regex("minSdk\\s*[=:]\\s*(\\d+)")
        .find(applicationSource)?.groupValues?.get(1)?.toIntOrNull() ?: minSdk
    return copy(javaVersion = detectedJavaVersion, compileSdk = sdk, minSdk = min)
  }

  private fun detectJavaVersion(source: String): Int? {
    if (source.isBlank()) return null
    val patterns = listOf(
        Regex("(?:jvmTarget|JvmTarget\\.fromTarget)\\s*[=(]\\s*[\\\"']?(?:1\\.)?(\\d+)", RegexOption.IGNORE_CASE),
        Regex("jvmTarget\\s*[=:]\\s*JvmTarget\\.fromTarget\\(\\s*[\\\"']?(?:1\\.)?(\\d+)", RegexOption.IGNORE_CASE),
        Regex("jvmTarget\\s*[=:]\\s*(?:JvmTarget\\.)?JVM_(?:1_)?(\\d+)", RegexOption.IGNORE_CASE),
        Regex("targetCompatibility\\s*[=:]\\s*(?:JavaVersion\\.)?VERSION_(?:1_)?(\\d+)", RegexOption.IGNORE_CASE),
        Regex("targetCompatibility\\s*[=:]\\s*[\\\"'](?:1\\.)?(\\d+)", RegexOption.IGNORE_CASE),
        Regex("sourceCompatibility\\s*[=:]\\s*(?:JavaVersion\\.)?VERSION_(?:1_)?(\\d+)", RegexOption.IGNORE_CASE),
        Regex("sourceCompatibility\\s*[=:]\\s*[\\\"'](?:1\\.)?(\\d+)", RegexOption.IGNORE_CASE),
    )
    return patterns.asSequence()
        .mapNotNull { it.find(source)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        .firstOrNull { it >= 8 }
  }

  private fun runtimeJavaVersion(): Int? {
    val value = System.getProperty("java.specification.version") ?: return null
    val normalized = value.removePrefix("1.").toIntOrNull() ?: return null
    return normalized.takeIf { it >= 8 }
  }

  private fun packageName(request: ModuleCreationRequest): String = request.sourcePackageName

  private fun generatedModuleFiles(request: ModuleCreationRequest): List<File> {
    val module = request.moduleDirectory
    val sourceFolder = if (request.sourceLanguage == ModuleSourceLanguage.KOTLIN) "kotlin" else "java"
    val sourceFile = if (request.sourceLanguage == ModuleSourceLanguage.KOTLIN) "Sample.kt" else "Sample.java"
    return buildList {
      add(File(module, request.moduleBuildFileName))
      add(File(module, "src/main/$sourceFolder/${request.sourcePackageName.replace('.', '/')}/$sourceFile"))
      if (request.kind == ModuleCreationKind.ANDROID_LIBRARY) {
        add(File(module, "src/main/AndroidManifest.xml"))
        add(File(module, "proguard-rules.pro"))
        add(File(module, "consumer-rules.pro"))
      }
    }
  }

  private fun writeModule(request: ModuleCreationRequest, buildScript: String) {
    val module = request.moduleDirectory
    val packageName = request.sourcePackageName
    val sourceFolder = if (request.sourceLanguage == ModuleSourceLanguage.KOTLIN) "kotlin" else "java"
    val packageDir = File(module, "src/main/$sourceFolder/${packageName.replace('.', '/')}")
    packageDir.mkdirs()
    File(module, request.moduleBuildFileName).writeText(buildScript.ensureTrailingNewline())
    File(packageDir, if (request.sourceLanguage == ModuleSourceLanguage.KOTLIN) "Sample.kt" else "Sample.java")
        .writeText(generateSample(request, packageName).ensureTrailingNewline())
    if (request.kind == ModuleCreationKind.ANDROID_LIBRARY) {
      File(module, "src/main/res").mkdirs()
      File(module, "src/main/AndroidManifest.xml").writeText("""
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <application />
        </manifest>
        """.trimIndent().ensureTrailingNewline())
      File(module, "proguard-rules.pro").writeText("# Module-specific ProGuard rules.\n")
      File(module, "consumer-rules.pro").writeText("# Consumer ProGuard rules for this library.\n")
    }
  }
  private fun generateBuildScript(request: ModuleCreationRequest, packageName: String): String {
    val kotlin = request.sourceLanguage == ModuleSourceLanguage.KOTLIN
    val javaVersion = request.javaVersion ?: 8
    val javaVersionExpression = "JavaVersion.toVersion(\"$javaVersion\")"
    val script = buildString {
      if (request.kind == ModuleCreationKind.JAVA_LIBRARY) {
        if (request.buildDsl.name == "KOTLIN") {
          appendLine("plugins {")
          appendLine("  id(\"java-library\")")
          if (kotlin) appendLine("  id(\"org.jetbrains.kotlin.jvm\")")
          appendLine("}")
          appendLine()
          appendLine("java {")
          appendLine("  sourceCompatibility = $javaVersionExpression")
          appendLine("  targetCompatibility = $javaVersionExpression")
          appendLine("}")
          if (kotlin) {
            appendLine()
            appendLine("kotlin {")
            appendLine("  compilerOptions {")
            appendLine("    jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(\"$javaVersion\")")
            appendLine("  }")
            appendLine("}")
          }
        } else {
          appendLine("plugins {")
          appendLine("  id 'java-library'")
          if (kotlin) appendLine("  id 'org.jetbrains.kotlin.jvm'")
          appendLine("}")
          appendLine()
          appendLine("java {")
          appendLine("  sourceCompatibility = $javaVersionExpression")
          appendLine("  targetCompatibility = $javaVersionExpression")
          appendLine("}")
          if (kotlin) {
            appendLine()
            appendLine("kotlin {")
            appendLine("  compilerOptions {")
            appendLine("    jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget('$javaVersion')")
            appendLine("  }")
            appendLine("}")
          }
        }
      } else if (request.buildDsl.name == "KOTLIN") {
        appendLine("plugins {")
        appendLine("  id(\"com.android.library\")")
        if (kotlin) appendLine("  id(\"kotlin-android\")")
        appendLine("}")
        appendLine()
        appendLine("android {")
        appendLine("  namespace = \"$packageName\"")
        appendLine("  compileSdk = ${request.compileSdk}")
        appendLine("  defaultConfig {")
        appendLine("    minSdk = ${request.minSdk}")
        appendLine("  }")
        appendLine("}")
      } else {
        appendLine("plugins {")
        appendLine("  id 'com.android.library'")
        if (kotlin) appendLine("  id 'kotlin-android'")
        appendLine("}")
        appendLine()
        appendLine("android {")
        appendLine("  namespace '$packageName'")
        appendLine("  compileSdk ${request.compileSdk}")
        appendLine("  defaultConfig {")
        appendLine("    minSdk ${request.minSdk}")
        appendLine("  }")
        appendLine("}")
      }
    }
    return script.ensureTrailingNewline()
  }


  private fun generateSample(request: ModuleCreationRequest, packageName: String): String =
      if (request.sourceLanguage == ModuleSourceLanguage.KOTLIN) """
        package $packageName
        class Sample {
          fun getGreeting(): String = "Hello from ${request.moduleName} module!"
        }
        """.trimIndent().plus("\n")
      else """
        package $packageName;
        public class Sample {
          public String getGreeting() {
            return "Hello from ${request.moduleName} module!";
          }
        }
        """.trimIndent()

  private fun String.ensureTrailingNewline(): String = if (endsWith("\n")) this else "$this\n"

  private fun findSettingsFile(root: File): File = listOf(File(root, "settings.gradle.kts"), File(root, "settings.gradle"))
      .firstOrNull { it.isFile } ?: throw IOException("settings.gradle(.kts) not found")

  private fun editorDsl(file: File) = if (file.name.endsWith(".kts")) EditorGradleDsl.KOTLIN else EditorGradleDsl.GROOVY

  private fun missingParentDirectories(root: File, module: File): List<File> {
    val result = mutableListOf<File>(); var current = module.parentFile
    while (current != null && current != root && !current.exists()) { result += current; current = current.parentFile }
    return result
  }

  private fun applyEdit(file: File, source: String, result: GradleEditResult, description: String, transaction: ProjectEditTransaction) =
      transaction.applyTextEdit(file, source, result, description)
}
