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

import java.io.File
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry

/** Registers the module-creation model and adds the server-owned probe only in memory. */
class ModuleCreationInitScriptPlugin : Plugin<Gradle> {
  companion object {
    private const val PROBE_PATH = "androidide.moduleCreationProbePath"
    private const val PROBE_DIRECTORY = "androidide.moduleCreationProbeDirectory"
  }

  override fun apply(target: Gradle) {
    target.settingsEvaluated { settings -> settings.includeModuleCreationProbe() }
    target.rootProject { rootProject ->
      rootProject.pluginManager.apply(ModuleCreationCapabilitiesPlugin::class.java)
    }
  }

  private fun Settings.includeModuleCreationProbe() {
    val path = startParameter.projectProperties[PROBE_PATH]?.toString()
    val directory = startParameter.projectProperties[PROBE_DIRECTORY]?.toString()
    if (path.isNullOrBlank() || directory.isNullOrBlank()) return

    val segments = path.trim().trim(':').split(':').filter(String::isNotBlank)
    if (segments.isEmpty() || segments.any { !it.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")) }) {
      throw GradleException("Module creation probe path is invalid: $path")
    }

    // Gradle creates implicit parent projects for nested paths. Map every prefix into the
    // server-owned probe tree so configuration never touches the user's project directory.
    include(path)
    var relativeDirectory = File(directory)
    segments.forEachIndexed { index, segment ->
      relativeDirectory = File(relativeDirectory, segment)
      val projectPath = ":${segments.take(index + 1).joinToString(":")}"
      project(projectPath).projectDir = relativeDirectory
    }
  }
}

class ModuleCreationCapabilitiesPlugin @Inject constructor(
    private val modelBuilderRegistry: ToolingModelBuilderRegistry,
) : Plugin<Project> {
  override fun apply(target: Project) {
    if (target != target.rootProject) return
    modelBuilderRegistry.register(ProjectCreationCapabilitiesModelBuilder())
  }
}