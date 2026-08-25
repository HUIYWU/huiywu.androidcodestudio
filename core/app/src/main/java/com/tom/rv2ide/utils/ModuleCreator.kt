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

package com.tom.rv2ide.utils

import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.tooling.api.models.GradleDsl
import com.tom.rv2ide.tooling.api.models.ApplicationProjectInfo
import com.tom.rv2ide.tooling.api.models.ModuleCreationKind
import com.tom.rv2ide.tooling.api.models.ModuleCreationValidation
import com.tom.rv2ide.tooling.api.models.ModuleCreationValidationRequest
import com.tom.rv2ide.tooling.api.models.ModuleSourceLanguage
import com.tom.rv2ide.tooling.api.models.ProjectCreationCapabilities
import com.tom.rv2ide.projects.gradleedit.BuildScriptDependenciesEditor
import com.tom.rv2ide.projects.gradleedit.GradleDsl as EditorGradleDsl
import com.tom.rv2ide.projects.gradleedit.GradleEditResult
import com.tom.rv2ide.projects.gradleedit.ProjectEditTransaction
import com.tom.rv2ide.projects.gradleedit.ProjectSettingsEditor
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Utility class for creating new modules in Android projects. Handles module structure
 * creation, build script generation, and settings file updates.
 *
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class ModuleCreator {

  private val log = LoggerFactory.getLogger(ModuleCreator::class.java)

  data class CreationResult(val success: Boolean, val errorMessage: String? = null)

  data class AppModuleConfig(val compileSdk: Int, val minSdk: Int)

  private data class ApplicationModule(val buildFile: File)

  private data class ModulePath(val gradlePath: String, val directoryPath: String, val name: String)

  fun getProjectCreationCapabilities(timeoutSeconds: Long = 30): ProjectCreationCapabilities? {
    val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) ?: return null
    if (!buildService.isToolingServerStarted() || buildService.isBuildInProgress) return null
    val future = runCatching { buildService.getProjectCreationCapabilities() }
        .onFailure { error ->
          log.warn("Failed to start project creation capabilities request", error)
        }
        .getOrNull() ?: return null
    return runCatching {
          future.get(timeoutSeconds, TimeUnit.SECONDS)
        }
        .onFailure { error ->
          log.warn("Timed out or failed while reading project creation capabilities", error)
          runCatching { buildService.cancelCurrentBuild() }
            .onFailure { cancellationError ->
              log.warn("Failed to request cancellation after capability lookup failure", cancellationError)
            }
        }
        .getOrNull()
  }

  private fun validateModuleCreation(
      moduleName: String,
      language: com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage,
      applicationModule: ApplicationModule,
      useKotlinDsl: Boolean,
  ): ModuleCreationValidation? {
    val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) ?: return null
    if (!buildService.isToolingServerStarted() || buildService.isBuildInProgress) return null
    val request =
        ModuleCreationValidationRequest(
            modulePath = moduleName.trim().let { if (it.startsWith(":")) it else ":$it" },
            kind = ModuleCreationKind.ANDROID_LIBRARY,
            sourceLanguage =
                if (language == com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage.KOTLIN) {
                  ModuleSourceLanguage.KOTLIN
                } else {
                  ModuleSourceLanguage.JAVA
                },
            buildDsl = if (useKotlinDsl) GradleDsl.KOTLIN else GradleDsl.GROOVY,
            compileSdk = detectAppModuleConfig(applicationModule.buildFile).compileSdk,
        )
    log.warn(
        "Requesting module creation probe; path={}, kind={}, language={}, dsl={}, compileSdk={}",
        request.modulePath,
        request.kind,
        request.sourceLanguage,
        request.buildDsl,
        request.compileSdk,
    )
    return runCatching { buildService.validateModuleCreation(request).get() }
        .onFailure { error -> log.warn("Module creation probe request failed; path={}", request.modulePath, error) }
        .getOrNull()
  }

  /** Runs the fail-closed Gradle configuration check before any project file is written. */
  fun preflightModuleCreation(
      moduleName: String,
      language: com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage,
      projectRoot: File,
      applicationPath: String,
      useKotlinDsl: Boolean = detectBuildScriptDsl(projectRoot, applicationPath),
      applicationInfo: ApplicationProjectInfo? = null,
  ): CreationResult {
    val modulePath = parseModulePath(moduleName)
        ?: return CreationResult(false, "Use a valid Gradle path such as :feature:profile")
    if (!projectRoot.isDirectory) {
      return CreationResult(false, "Project root directory does not exist")
    }
    val applicationModule = resolveApplicationModule(projectRoot, applicationPath, applicationInfo)
        ?: return CreationResult(false, "Selected application module '$applicationPath' is unavailable")
    if (File(projectRoot, modulePath.directoryPath).exists()) {
      return CreationResult(false, "Module '${modulePath.gradlePath}' already exists")
    }

    val validation = validateModuleCreation(modulePath.gradlePath, language, applicationModule, useKotlinDsl)
        ?: return CreationResult(
            false,
            "Module creation validation is unavailable. Wait for Gradle synchronization to finish and try again.",
        )
    if (!validation.isValid) {
      return CreationResult(
          false,
          validation.message ?: "This module configuration cannot be applied to the current Gradle project.",
      )
    }
    return CreationResult(true)
  }

  /**
   * Creates a new module after a successful preflight check.
   *
   * This repeats the preflight to keep direct callers fail-closed.
   */
  fun createModule(
      moduleName: String,
      language: com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage,
      projectRoot: File,
      applicationPath: String,
      useKotlinDsl: Boolean = detectBuildScriptDsl(projectRoot, applicationPath),
      applicationInfo: ApplicationProjectInfo? = null,
  ): CreationResult {
    val preflight = preflightModuleCreation(
        moduleName,
        language,
        projectRoot,
        applicationPath,
        useKotlinDsl,
        applicationInfo,
    )
    if (!preflight.success) return preflight
    return createPreflightValidatedModule(
        moduleName,
        language,
        projectRoot,
        applicationPath,
        useKotlinDsl,
        applicationInfo,
    )
  }

  /** Writes module files after [preflightModuleCreation] has succeeded for the same request. */
  fun createPreflightValidatedModule(
      moduleName: String,
      language: com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage,
      projectRoot: File,
      applicationPath: String,
      useKotlinDsl: Boolean = detectBuildScriptDsl(projectRoot, applicationPath),
      applicationInfo: ApplicationProjectInfo? = null,
  ): CreationResult {
    val modulePath = parseModulePath(moduleName)
        ?: return CreationResult(false, "Use a valid Gradle path such as :feature:profile")
    val applicationModule = resolveApplicationModule(projectRoot, applicationPath, applicationInfo)
        ?: return CreationResult(false, "Selected application module '$applicationPath' is unavailable")
    val moduleDir = File(projectRoot, modulePath.directoryPath)
    if (moduleDir.exists()) {
      return CreationResult(false, "Module '${modulePath.gradlePath}' already exists")
    }

    val transaction =
        runCatching {
          ProjectEditTransaction.begin(projectRoot, listOf(applicationModule.buildFile.parentFile)).also { edit ->
            edit.capture(findSettingsFile(projectRoot))
            edit.capture(applicationModule.buildFile)
            missingParentDirectories(projectRoot, moduleDir).forEach(edit::trackCreatedParentDirectory)
            edit.trackCreatedDirectory(moduleDir)
          }
        }.getOrElse { error ->
          return CreationResult(false, error.message ?: "Could not prepare project edit transaction")
        }
    return try {
      val basePackageName = detectBasePackageName(applicationModule.buildFile)
      val appConfig = detectAppModuleConfig(applicationModule.buildFile)
      createModuleStructure(
          moduleDir,
          modulePath.name.replace('-', '_'),
          language,
          useKotlinDsl,
          basePackageName,
          appConfig,
      )
      updateSettingsGradle(projectRoot, modulePath.gradlePath, transaction)
      addDependencyToApplicationModule(applicationModule, modulePath.gradlePath, transaction)
      transaction.commit()
      CreationResult(true)
    } catch (e: Exception) {
      // Creation changes several user-owned files. Restore them before removing only directories this call made.
      transaction.rollback().forEach { rollbackError ->
        log.warn("Could not fully roll back module creation; name={}", moduleName, rollbackError)
      }
      log.warn("Module creation failed while preparing project files; name={}", moduleName, e)
      CreationResult(false, e.message ?: "Unknown error occurred")
    }
  }

  private fun findSettingsFile(projectRoot: File): File =
      listOf(File(projectRoot, "settings.gradle.kts"), File(projectRoot, "settings.gradle"))
          .firstOrNull { it.isFile }
          ?: throw IOException("settings.gradle(.kts) not found in project root")

  private fun missingParentDirectories(projectRoot: File, moduleDir: File): List<File> {
    val directories = mutableListOf<File>()
    var directory = moduleDir.parentFile
    while (directory != null && directory != projectRoot && !directory.exists()) {
      directories += directory
      directory = directory.parentFile
    }
    return directories
  }

  private fun resolveApplicationModule(
      projectRoot: File,
      applicationPath: String,
      applicationInfo: ApplicationProjectInfo? = null,
  ): ApplicationModule? {
    if (applicationInfo != null && applicationInfo.gradlePath == applicationPath.trim()) {
      val directory = File(applicationInfo.projectDirectory)
      val buildFile = File(applicationInfo.buildFile)
      if (directory.isDirectory && buildFile.isFile) return ApplicationModule(buildFile)
    }
    val normalizedPath = applicationPath.trim()
    val projectDirectory =
        if (normalizedPath == ":") {
          projectRoot
        } else {
          val segments = normalizedPath.trim(':').split(':')
          if (segments.isEmpty() || segments.any { !it.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")) }) return null
          File(projectRoot, segments.joinToString(File.separator))
        }
    val buildFile =
        listOf(File(projectDirectory, "build.gradle.kts"), File(projectDirectory, "build.gradle"))
            .firstOrNull { it.isFile } ?: return null
    return ApplicationModule(buildFile)
  }

  private fun detectBuildScriptDsl(projectRoot: File, applicationPath: String): Boolean =
      resolveApplicationModule(projectRoot, applicationPath)?.buildFile?.name?.endsWith(".kts") ?: true

  private fun detectBasePackageName(buildFile: File): String {
    if (buildFile.isFile) {
      val content = buildFile.readText()
      val namespacePattern = Regex("namespace\\s*[=:]\\s*[\"']([^\"']+)[\"']")
      val applicationIdPattern = Regex("applicationId\\s*[=:]\\s*[\"']([^\"']+)[\"']")

      val namespaceMatch = namespacePattern.find(content)
      if (namespaceMatch != null) {
        return namespaceMatch.groupValues[1]
      }

      val applicationIdMatch = applicationIdPattern.find(content)
      if (applicationIdMatch != null) {
        return applicationIdMatch.groupValues[1]
      }
    }
    return "com.example"
  }

  private fun detectAppModuleConfig(buildFile: File): AppModuleConfig {
    var compileSdk = 34 // Default fallback
    var minSdk = 21 // Default fallback

    if (buildFile.isFile) {
      val content = buildFile.readText()
      val compileSdkPattern = Regex("compileSdk\\s*[=:]\\s*(\\d+)")
      val compileSdkMatch = compileSdkPattern.find(content)
      if (compileSdkMatch != null) {
        compileSdk = compileSdkMatch.groupValues[1].toIntOrNull() ?: 34
      }
      val minSdkPattern = Regex("minSdk\\s*[=:]\\s*(\\d+)")
      val minSdkMatch = minSdkPattern.find(content)
      if (minSdkMatch != null) {
        minSdk = minSdkMatch.groupValues[1].toIntOrNull() ?: 21
      }
    }

    return AppModuleConfig(compileSdk, minSdk)
  }

  private fun createModuleStructure(
      moduleDir: File,
      moduleName: String,
      language: com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage,
      useKotlinDsl: Boolean,
      basePackageName: String,
      appConfig: AppModuleConfig,
  ) {
    val srcMainDir = File(moduleDir, "src/main")
    val javaDir =
        File(
            srcMainDir,
            if (
                language == com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage.KOTLIN
            )
                "kotlin"
            else "java",
        )
    val resourcesDir = File(srcMainDir, "resources")

    moduleDir.mkdirs()
    srcMainDir.mkdirs()
    javaDir.mkdirs()
    resourcesDir.mkdirs()

    createBuildGradle(moduleDir, moduleName, language, useKotlinDsl, basePackageName, appConfig)
    createProguardRules(moduleDir)
    createConsumerRules(moduleDir)
    createSampleSourceFile(javaDir, moduleName, language, basePackageName)
  }

  private fun createBuildGradle(
      moduleDir: File,
      moduleName: String,
      language: com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage,
      useKotlinDsl: Boolean,
      basePackageName: String,
      appConfig: AppModuleConfig,
  ) {
    val buildFile = File(moduleDir, if (useKotlinDsl) "build.gradle.kts" else "build.gradle")

    val content =
        if (useKotlinDsl) {
          generateKotlinDslBuildScript(moduleName, language, basePackageName, appConfig)
        } else {
          generateGroovyBuildScript(moduleName, language, basePackageName, appConfig)
        }

    buildFile.writeText(content)
  }

  private fun generateKotlinDslBuildScript(
      moduleName: String,
      language: com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage,
      basePackageName: String,
      appConfig: AppModuleConfig,
  ): String {
    val kotlinPlugin =
        if (language == com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage.KOTLIN) {
          "id(\"kotlin-android\")"
        } else {
          "// Java module - no additional plugin needed"
        }

    val kotlinOptions =
        if (language == com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage.KOTLIN) {
          """
  kotlinOptions {
    jvmTarget = "1.8"
  }"""
        } else {
          ""
        }

    return """
plugins {
  id("com.android.library")
  $kotlinPlugin
}

android {
  namespace = "$basePackageName.$moduleName"
  compileSdk = ${appConfig.compileSdk}

  defaultConfig {
    minSdk = ${appConfig.minSdk}
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
  }
$kotlinOptions
}

dependencies {
  // Core Android dependencies
  implementation("androidx.annotation:annotation:1.7.0")
}
"""
        .trimIndent()
  }

  private fun generateGroovyBuildScript(
      moduleName: String,
      language: com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage,
      basePackageName: String,
      appConfig: AppModuleConfig,
  ): String {
    val kotlinPlugin =
        if (language == com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage.KOTLIN) {
          "id 'kotlin-android'"
        } else {
          "// Java module - no additional plugin needed"
        }

    val kotlinOptions =
        if (language == com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage.KOTLIN) {
          """
  kotlinOptions {
    jvmTarget = '1.8'
  }"""
        } else {
          ""
        }

    return """
plugins {
  id 'com.android.library'
  $kotlinPlugin
}

android {
  namespace '$basePackageName.$moduleName'
  compileSdk ${appConfig.compileSdk}

  defaultConfig {
    minSdk ${appConfig.minSdk}
  }

  buildTypes {
    release {
      minifyEnabled false
      proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
    }
  }

  compileOptions {
    sourceCompatibility JavaVersion.VERSION_1_8
    targetCompatibility JavaVersion.VERSION_1_8
  }
$kotlinOptions
}

dependencies {
  // Core Android dependencies
  implementation 'androidx.annotation:annotation:1.7.0'
}
"""
        .trimIndent()
  }

  private fun createProguardRules(moduleDir: File) {
    val proguardFile = File(moduleDir, "proguard-rules.pro")
    proguardFile.writeText(
        """
        # Add project specific ProGuard rules here.
        # You can control the set of applied configuration files using the
        # proguardFiles setting in build.gradle.
        #
        # For more details, see
        #   http://developer.android.com/guide/developing/tools/proguard.html

        # If your project uses WebView with JS, uncomment the following
        # and specify the fully qualified class name to the JavaScript interface
        # class:
        #-keepclassmembers class fqcn.of.javascript.interface.for.webview {
        #   public *;
        #}

        # Uncomment this to preserve the line number information for
        # debugging stack traces.
        #-keepattributes SourceFile,LineNumberTable

        # If you keep the line number information, uncomment this to
        # hide the original source file name.
        #-renamesourcefileattribute SourceFile
        """
            .trimIndent()
    )
  }

  private fun createConsumerRules(moduleDir: File) {
    val consumerRulesFile = File(moduleDir, "consumer-rules.pro")
    consumerRulesFile.writeText(
        """
        # Consumer ProGuard rules for this module
        # These rules will be applied to consumers of this library
        """
            .trimIndent()
    )
  }

  private fun createSampleSourceFile(
      sourceDir: File,
      moduleName: String,
      language: com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage,
      basePackageName: String,
  ) {
    val packageDir = File(sourceDir, basePackageName.replace(".", "/") + "/$moduleName")
    packageDir.mkdirs()

    val fileName =
        if (language == com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage.KOTLIN) {
          "SampleClass.kt"
        } else {
          "SampleClass.java"
        }

    val sampleFile = File(packageDir, fileName)

    val content =
        if (language == com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage.KOTLIN) {
          """
package $basePackageName.$moduleName

/**
 * Sample class for the $moduleName module.
 */
class SampleClass {

    /**
     * Sample method that returns a greeting message.
     */
    fun getGreeting(): String {
        return "Hello from $moduleName module!"
    }
}
"""
              .trimIndent()
        } else {
          """
package $basePackageName.$moduleName;

/**
 * Sample class for the $moduleName module.
 */
public class SampleClass {

    /**
     * Sample method that returns a greeting message.
     */
    public String getGreeting() {
        return "Hello from $moduleName module!";
    }
}
"""
              .trimIndent()
        }

    sampleFile.writeText(content)
  }
  private fun parseModulePath(value: String): ModulePath? {
    val segments = value.trim().trim(':').split(':')
    if (segments.isEmpty() || segments.any { !it.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")) }) return null
    return ModulePath(
        gradlePath = ":${segments.joinToString(":")}",
        directoryPath = segments.joinToString(File.separator),
        name = segments.last(),
    )
  }

  private fun updateSettingsGradle(
      projectRoot: File,
      modulePath: String,
      transaction: ProjectEditTransaction,
  ) {
    val settingsFile = findSettingsFile(projectRoot)
    val source = settingsFile.readText()
    val result = ProjectSettingsEditor.addInclude(
        source,
        modulePath,
        if (settingsFile.name.endsWith(".kts")) EditorGradleDsl.KOTLIN else EditorGradleDsl.GROOVY,
    )
    applyGradleEdit(settingsFile, source, result, "settings file", transaction)
  }

  private fun addDependencyToApplicationModule(
      applicationModule: ApplicationModule,
      modulePath: String,
      transaction: ProjectEditTransaction,
  ) {
    val appBuildFile = applicationModule.buildFile
    val source = appBuildFile.readText()
    val dsl = if (appBuildFile.name.endsWith(".kts")) EditorGradleDsl.KOTLIN else EditorGradleDsl.GROOVY
    val result = BuildScriptDependenciesEditor.addProjectDependency(
        source = source,
        configuration = "implementation",
        gradlePath = modulePath,
        dsl = dsl,
    )
    applyGradleEdit(appBuildFile, source, result, "application build script", transaction)
  }

  private fun applyGradleEdit(
      file: File,
      source: String,
      result: GradleEditResult,
      description: String,
      transaction: ProjectEditTransaction,
  ) = transaction.applyTextEdit(file, source, result, description)
}
