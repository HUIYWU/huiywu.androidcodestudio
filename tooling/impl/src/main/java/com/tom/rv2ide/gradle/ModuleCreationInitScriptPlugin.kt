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
import org.gradle.api.logging.Logging
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry

/** Registers the module-creation model and adds the server-owned probe only in memory. */
class ModuleCreationInitScriptPlugin : Plugin<Gradle> {
  companion object {
    private const val PROBE_PATH = "androidide.moduleCreationProbePath"
    private const val PROBE_DIRECTORY = "androidide.moduleCreationProbeDirectory"
    private val logger = Logging.getLogger(ModuleCreationInitScriptPlugin::class.java)
  }

  override fun apply(target: Gradle) {
    logger.warn("Module creation capability init plugin applied; gradle={}", target.gradleVersion)
    target.settingsEvaluated { settings -> settings.includeModuleCreationProbe() }
    target.rootProject { rootProject ->
      logger.warn("Registering module creation capability plugin; root={}", rootProject.path)
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
  private val logger = Logging.getLogger(ModuleCreationCapabilitiesPlugin::class.java)

  override fun apply(target: Project) {
    if (target != target.rootProject) return
    logger.warn("Registering module creation capability builder; root={}", target.path)
    modelBuilderRegistry.register(ProjectCreationCapabilitiesModelBuilder())
    logger.warn("Registered module creation capability builder; root={}", target.path)
  }
}