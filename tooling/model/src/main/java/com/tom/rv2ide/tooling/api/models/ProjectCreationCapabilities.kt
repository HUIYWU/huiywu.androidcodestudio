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

/** Runtime Gradle capabilities used to plan module creation. */
interface ProjectCreationCapabilities : Serializable {
  val projectRoot: String
  val settingsDsl: GradleDsl
  val applicationProjects: List<String>
  val candidates: List<ModuleCreationCandidate>
  val diagnostics: List<CreationCapabilityDiagnostic>
}

/** Builder-side implementation of the Tooling API model contract. */
data class DefaultProjectCreationCapabilities(
    override val projectRoot: String,
    override val settingsDsl: GradleDsl,
    override val applicationProjects: List<String>,
    override val candidates: List<ModuleCreationCandidate>,
    override val diagnostics: List<CreationCapabilityDiagnostic>,
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

enum class PluginApplicationStyle : Serializable {
  PLUGINS_BLOCK,
  LEGACY_APPLY,
  UNKNOWN,
}

data class ModuleCreationCandidate(
    val id: String,
    val kind: ModuleCreationKind,
    val sourceLanguage: ModuleSourceLanguage,
    val buildDsl: GradleDsl,
    val pluginStyle: PluginApplicationStyle,
    val sourceProjectPath: String?,
) : Serializable {
  companion object {
    private const val serialVersionUID = 1L
  }
}

data class CreationCapabilityDiagnostic(
    val severity: CreationCapabilityDiagnosticSeverity,
    val message: String,
) : Serializable {
  companion object {
    private const val serialVersionUID = 1L
  }
}

enum class CreationCapabilityDiagnosticSeverity : Serializable {
  INFO,
  WARNING,
  ERROR,
}
