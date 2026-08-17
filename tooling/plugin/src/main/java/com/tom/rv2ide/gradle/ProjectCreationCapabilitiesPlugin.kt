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
package com.tom.rv2ide.gradle

import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logging
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry

/** Registers the module creation capability model for the root project. */
class ProjectCreationCapabilitiesPlugin @Inject constructor(
    private val modelBuilderRegistry: ToolingModelBuilderRegistry,
) : Plugin<Project> {

  private val logger = Logging.getLogger(ProjectCreationCapabilitiesPlugin::class.java)

  override fun apply(target: Project) {
    if (target != target.rootProject) {
      logger.warn("Skipping module creation capability builder registration for non-root project={}", target.path)
      return
    }
    logger.warn(
        "Registering module creation capability builder; root={}, registryType={}, pluginLoader={}",
        target.path,
        modelBuilderRegistry.javaClass.name,
        javaClass.classLoader,
    )
    modelBuilderRegistry.register(ProjectCreationCapabilitiesModelBuilder())
    logger.warn("Registered module creation capability builder; root={}", target.path)
  }
}