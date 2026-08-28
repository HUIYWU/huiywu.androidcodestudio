package com.tom.rv2ide.utils

import com.tom.rv2ide.tooling.api.models.ApplicationProjectInfo
import com.tom.rv2ide.tooling.api.models.GradleDsl
import com.tom.rv2ide.tooling.api.models.ModuleCreationKind
import com.tom.rv2ide.tooling.api.models.ModuleSourceLanguage
import java.io.File

/** Shared input for module validation, preview, file generation, and Gradle edits. */
data class ModuleCreationRequest(
    val gradlePath: String,
    val kind: ModuleCreationKind,
    val sourceLanguage: ModuleSourceLanguage,
    val buildDsl: GradleDsl,
    val projectRoot: File,
    val consumerProjectPath: String? = null,
    val applicationProject: ApplicationProjectInfo? = null,
    val compileSdk: Int = 34,
    val minSdk: Int = 21,
) {
  val moduleDirectory: File
    get() = File(projectRoot, gradlePath.trim(':').replace(':', File.separator))

  val moduleName: String
    get() = gradlePath.trim(':').substringAfterLast(':')

  val moduleBuildFileName: String
    get() = if (buildDsl == GradleDsl.KOTLIN) "build.gradle.kts" else "build.gradle"

  val settingsFileName: String
    get() = if (buildDsl == GradleDsl.KOTLIN) "settings.gradle.kts" else "settings.gradle"

  companion object {
    fun normalizePath(value: String): String? {
      val segments = value.trim().trim(':').split(':')
      if (segments.isEmpty() || segments.any { !it.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")) }) return null
      return ":${segments.joinToString(":")}"
    }
  }
}
