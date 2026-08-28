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
package com.tom.rv2ide.tooling.api.models

import java.io.Serializable

/** Project information used by module creation, primarily for optional Application consumers. */
interface ProjectCreationCapabilities : Serializable {
  val projectRoot: String
  val settingsDsl: GradleDsl
  val applicationProjects: List<String>
  val applicationProjectDetails: List<ApplicationProjectInfo>
}

/** A configured Android application project with its actual Gradle file locations. */
interface ApplicationProjectInfo : Serializable {
  val gradlePath: String
  val projectDirectory: String
  val buildFile: String
}

data class DefaultApplicationProjectInfo(
    override val gradlePath: String,
    override val projectDirectory: String,
    override val buildFile: String,
) : ApplicationProjectInfo

/** Builder-side implementation of the Tooling API model contract. */
data class DefaultProjectCreationCapabilities(
    override val projectRoot: String,
    override val settingsDsl: GradleDsl,
    override val applicationProjects: List<String>,
    override val applicationProjectDetails: List<DefaultApplicationProjectInfo>,
) : ProjectCreationCapabilities {
  companion object {
    private const val serialVersionUID = 1L
  }
}

enum class GradleDsl : Serializable {
  GROOVY,
  KOTLIN,
  UNKNOWN,
}

enum class ModuleCreationKind : Serializable {
  ANDROID_LIBRARY,
  JAVA_LIBRARY,
}

enum class ModuleSourceLanguage : Serializable {
  JAVA,
  KOTLIN,
}
