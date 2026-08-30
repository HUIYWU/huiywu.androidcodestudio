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
    /** Namespace for Android libraries, package name for JVM libraries. */
    val sourcePackageName: String,
    /** Optional project-relative directory used when existing generated files may be overwritten. */
    val overrideModuleDirectory: File? = null,
    val consumerProjectPath: String? = null,
    val applicationProject: ApplicationProjectInfo? = null,
    /** Java/Kotlin target used by JVM libraries; resolved from the selected Application when absent. */
    val javaVersion: Int? = null,
    val compileSdk: Int = 34,
    val minSdk: Int = 21,
) {
  val moduleDirectory: File
    get() = overrideModuleDirectory ?: File(projectRoot, gradlePath.trim(':').replace(":", File.separator))

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
