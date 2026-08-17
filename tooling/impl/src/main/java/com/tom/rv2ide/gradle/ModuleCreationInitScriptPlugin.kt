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
    include(path)
    project(path).projectDir = File(directory)
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