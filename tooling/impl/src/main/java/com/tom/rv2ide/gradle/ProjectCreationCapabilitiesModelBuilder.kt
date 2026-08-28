/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio. If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.gradle

import com.tom.rv2ide.tooling.api.models.DefaultApplicationProjectInfo
import com.tom.rv2ide.tooling.api.models.DefaultProjectCreationCapabilities
import com.tom.rv2ide.tooling.api.models.GradleDsl
import com.tom.rv2ide.tooling.api.models.ProjectCreationCapabilities
import java.io.File
import org.gradle.api.Project
import org.gradle.tooling.provider.model.ToolingModelBuilder

private const val APP_PLUGIN = "com.android.application"

/** Exposes project information needed to choose an optional Application consumer. */
class ProjectCreationCapabilitiesModelBuilder : ToolingModelBuilder {
  override fun canBuild(modelName: String): Boolean =
      modelName == ProjectCreationCapabilities::class.java.name

  override fun buildAll(modelName: String, project: Project): Any {
    val rootProject = project.rootProject
    val applications = rootProject.allprojects.filter { it.pluginManager.hasPlugin(APP_PLUGIN) }
    return DefaultProjectCreationCapabilities(
        projectRoot = rootProject.rootDir.absolutePath,
        settingsDsl = detectSettingsDsl(rootProject.rootDir),
        applicationProjects = applications.map(Project::getPath).sorted(),
        applicationProjectDetails = applications.mapNotNull(::applicationProjectInfo)
            .sortedBy(DefaultApplicationProjectInfo::gradlePath),
    )
  }

  private fun applicationProjectInfo(project: Project): DefaultApplicationProjectInfo? {
    val buildFile = project.buildFile
    if (!buildFile.isFile) return null
    return DefaultApplicationProjectInfo(
        gradlePath = project.path,
        projectDirectory = project.projectDir.absolutePath,
        buildFile = buildFile.absolutePath,
    )
  }

  private fun detectSettingsDsl(rootDir: File) = when {
    File(rootDir, "settings.gradle.kts").isFile -> GradleDsl.KOTLIN
    File(rootDir, "settings.gradle").isFile -> GradleDsl.GROOVY
    else -> GradleDsl.UNKNOWN
  }
}