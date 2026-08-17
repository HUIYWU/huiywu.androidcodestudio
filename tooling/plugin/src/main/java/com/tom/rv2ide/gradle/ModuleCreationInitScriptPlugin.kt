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
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logging

/** Installs only the isolated module-creation Tooling model and probe settings. */
class ModuleCreationInitScriptPlugin : Plugin<Gradle> {

  companion object {
    private const val MODULE_CREATION_PROBE_PATH = "androidide.moduleCreationProbePath"
    private const val MODULE_CREATION_PROBE_DIRECTORY = "androidide.moduleCreationProbeDirectory"
    private val logger = Logging.getLogger(ModuleCreationInitScriptPlugin::class.java)
  }

  override fun apply(target: Gradle) {
    logger.warn("Module creation capability init plugin applied; gradle={}", target.gradleVersion)
    target.settingsEvaluated { settings -> settings.includeModuleCreationProbe() }
    target.rootProject { rootProject ->
      logger.warn("Registering module creation capability plugin; root={}", rootProject.path)
      rootProject.pluginManager.apply(ProjectCreationCapabilitiesPlugin::class.java)
    }
  }

  private fun Settings.includeModuleCreationProbe() {
    val probePath = startParameter.projectProperties[MODULE_CREATION_PROBE_PATH]?.toString()
    val probeDirectory = startParameter.projectProperties[MODULE_CREATION_PROBE_DIRECTORY]?.toString()
    if (probePath.isNullOrBlank() || probeDirectory.isNullOrBlank()) return

    include(probePath)
    project(probePath).projectDir = File(probeDirectory)
  }
}
