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
import com.tom.rv2ide.tooling.api.models.ModuleCreationKind
import com.tom.rv2ide.tooling.api.models.ModuleCreationValidation
import com.tom.rv2ide.tooling.api.models.ModuleCreationValidationRequest
import com.tom.rv2ide.tooling.api.models.ModuleSourceLanguage
import com.tom.rv2ide.tooling.api.models.ProjectCreationCapabilities
import java.io.File
import java.io.IOException
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

  private data class ModulePath(val gradlePath: String, val directoryPath: String, val name: String)

  fun getProjectCreationCapabilities(): ProjectCreationCapabilities? {
    val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) ?: return null
    if (!buildService.isToolingServerStarted() || buildService.isBuildInProgress) return null
    return runCatching { buildService.getProjectCreationCapabilities().get() }.getOrNull()
  }

  private fun validateModuleCreation(
      moduleName: String,
      language: com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage,
      projectRoot: File,
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
            compileSdk = detectAppModuleConfig(projectRoot).compileSdk,
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
      useKotlinDsl: Boolean = detectBuildScriptDsl(projectRoot),
  ): CreationResult {
    val modulePath = parseModulePath(moduleName)
        ?: return CreationResult(false, "Use a valid Gradle path such as :feature:profile")
    if (!projectRoot.isDirectory) {
      return CreationResult(false, "Project root directory does not exist")
    }
    if (File(projectRoot, modulePath.directoryPath).exists()) {
      return CreationResult(false, "Module '${modulePath.gradlePath}' already exists")
    }

    val validation = validateModuleCreation(modulePath.gradlePath, language, projectRoot, useKotlinDsl)
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
      useKotlinDsl: Boolean = detectBuildScriptDsl(projectRoot),
  ): CreationResult {
    val preflight = preflightModuleCreation(moduleName, language, projectRoot, useKotlinDsl)
    if (!preflight.success) return preflight
    return createPreflightValidatedModule(moduleName, language, projectRoot, useKotlinDsl)
  }

  /** Writes module files after [preflightModuleCreation] has succeeded for the same request. */
  fun createPreflightValidatedModule(
      moduleName: String,
      language: com.tom.rv2ide.fragments.sidebar.ModuleManagerFragment.ModuleLanguage,
      projectRoot: File,
      useKotlinDsl: Boolean = detectBuildScriptDsl(projectRoot),
  ): CreationResult {
    return try {
      val modulePath = parseModulePath(moduleName)
          ?: return CreationResult(false, "Use a valid Gradle path such as :feature:profile")
      val moduleDir = File(projectRoot, modulePath.directoryPath)
      if (moduleDir.exists()) {
        return CreationResult(false, "Module '${modulePath.gradlePath}' already exists")
      }
      val appUsesKotlinDsl = detectBuildScriptDsl(projectRoot)
      val basePackageName = detectBasePackageName(projectRoot)
      val appConfig = detectAppModuleConfig(projectRoot)
      createModuleStructure(
          moduleDir,
          modulePath.name.replace('-', '_'),
          language,
          useKotlinDsl,
          basePackageName,
          appConfig,
      )
      updateSettingsGradle(projectRoot, modulePath.gradlePath)
      addDependencyToAppModule(projectRoot, modulePath.gradlePath, appUsesKotlinDsl)
      CreationResult(true)
    } catch (e: Exception) {
      log.warn("Module creation failed while preparing project files; name={}", moduleName, e)
      CreationResult(false, e.message ?: "Unknown error occurred")
    }
  }

  private fun detectBuildScriptDsl(projectRoot: File): Boolean {
    val appBuildFileKts = File(projectRoot, "app/build.gradle.kts")
    val appBuildFileGroovy = File(projectRoot, "app/build.gradle")
    if (appBuildFileKts.exists()) {
      return true
    }
    if (appBuildFileGroovy.exists()) {
      return false
    }
    return true
  }

  private fun detectBasePackageName(projectRoot: File): String {
    val appBuildFileKts = File(projectRoot, "app/build.gradle.kts")
    val appBuildFileGroovy = File(projectRoot, "app/build.gradle")

    val buildFile = if (appBuildFileKts.exists()) appBuildFileKts else appBuildFileGroovy

    if (buildFile.exists()) {
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

  private fun detectAppModuleConfig(projectRoot: File): AppModuleConfig {
    val appBuildFileKts = File(projectRoot, "app/build.gradle.kts")
    val appBuildFileGroovy = File(projectRoot, "app/build.gradle")

    val buildFile = if (appBuildFileKts.exists()) appBuildFileKts else appBuildFileGroovy

    var compileSdk = 34 // Default fallback
    var minSdk = 21 // Default fallback

    if (buildFile.exists()) {
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

  private fun updateSettingsGradle(projectRoot: File, modulePath: String) {

    val kotlinSettings = File(projectRoot, "settings.gradle.kts")
    val groovySettings = File(projectRoot, "settings.gradle")
    val settingsFile = when {
      kotlinSettings.isFile -> kotlinSettings
      groovySettings.isFile -> groovySettings
      else -> throw IOException("settings.gradle(.kts) not found in project root")
    }

    val content = settingsFile.readText()

    // Check if module is already included
    if (content.contains("\"$modulePath\"") || content.contains("'$modulePath'")) {
      return // Module already included
    }

    val includePattern = Regex("include\\s*\\(\\s*([^)]*)\\s*\\)")
    val match = includePattern.find(content)

    if (match != null) {
      val existingModules = match.groupValues[1].trim()
      val newModuleEntry = modulePath

      if (existingModules.isNotEmpty()) {
        val newContent =
            content.replace(
                match.groupValues[0],
                "include(\n  $existingModules,\n  \"$newModuleEntry\"\n)",
            )
        settingsFile.writeText(newContent)
      } else {
        val newContent = content.replace(match.groupValues[0], "include(\"$newModuleEntry\")")
        settingsFile.writeText(newContent)
      }
    } else {
      val newContent = content + "\n\ninclude(\"$modulePath\")\n"
      settingsFile.writeText(newContent)
    }
  }

  private fun addDependencyToAppModule(
      projectRoot: File,
      modulePath: String,
      useKotlinDsl: Boolean,
  ) {
    val appBuildFile =
        File(projectRoot, if (useKotlinDsl) "app/build.gradle.kts" else "app/build.gradle")
    if (!appBuildFile.exists()) {
      return // App module doesn't exist, skip
    }

    val content = appBuildFile.readText()

    // Check if dependency is already added
    if (
        content.contains("project(\"$modulePath\")") || content.contains("project('$modulePath')")
    ) {
      return // Dependency already exists
    }

    // Find the dependencies block and add the new module dependency
    val dependenciesPattern = Regex("dependencies\\s*\\{")
    val match = dependenciesPattern.find(content)

    if (match != null) {
      val insertPosition = match.range.last + 1
      val dependencyLine =
          if (useKotlinDsl) {
            "\n    implementation(project(\"$modulePath\"))\n"
          } else {
            "\n    implementation project('$modulePath')\n"
          }

      val newContent =
          content.substring(0, insertPosition) + dependencyLine + content.substring(insertPosition)

      appBuildFile.writeText(newContent)
    }
  }
}
