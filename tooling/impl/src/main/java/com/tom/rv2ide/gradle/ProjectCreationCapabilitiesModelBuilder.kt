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

import com.tom.rv2ide.tooling.api.models.CreationCapabilityDiagnostic
import com.tom.rv2ide.tooling.api.models.CreationCapabilityDiagnosticSeverity
import com.tom.rv2ide.tooling.api.models.DefaultProjectCreationCapabilities
import com.tom.rv2ide.tooling.api.models.GradleDsl
import com.tom.rv2ide.tooling.api.models.ModuleCreationCandidate
import com.tom.rv2ide.tooling.api.models.ModuleCreationKind
import com.tom.rv2ide.tooling.api.models.ModuleSourceLanguage
import com.tom.rv2ide.tooling.api.models.PluginApplicationStyle
import com.tom.rv2ide.tooling.api.models.ProjectCreationCapabilities
import java.io.File
import org.gradle.api.Project
import org.gradle.api.logging.Logging
import org.gradle.tooling.provider.model.ToolingModelBuilder

private const val APP_PLUGIN = "com.android.application"
private const val LIBRARY_PLUGIN = "com.android.library"

/** Exposes configured Gradle module creation capabilities to the Tooling API. */
class ProjectCreationCapabilitiesModelBuilder : ToolingModelBuilder {
  private val logger = Logging.getLogger(ProjectCreationCapabilitiesModelBuilder::class.java)

  override fun canBuild(modelName: String): Boolean {
    val supported = modelName == ProjectCreationCapabilities::class.java.name
    logger.warn(
        "Module creation capability builder queried; requestedModel={}, supported={}, builderLoader={}",
        modelName, supported, javaClass.classLoader,
    )
    return supported
  }

  override fun buildAll(modelName: String, project: Project): Any {
    logger.warn(
        "Module creation capability builder invoked; requestedModel={}, project={}, root={}",
        modelName, project.path, project.rootProject.path,
    )
    val rootProject = project.rootProject
    val candidates = rootProject.allprojects.flatMap(::candidatesFor)
    val diagnostics = mutableListOf<CreationCapabilityDiagnostic>()
    if (candidates.none { it.kind == ModuleCreationKind.ANDROID_LIBRARY }) {
      diagnostics.add(CreationCapabilityDiagnostic(
          CreationCapabilityDiagnosticSeverity.WARNING,
          "No configured Android library project was found.",
      ))
    }
    if (candidates.none { it.sourceLanguage == ModuleSourceLanguage.KOTLIN }) {
      diagnostics.add(CreationCapabilityDiagnostic(
          CreationCapabilityDiagnosticSeverity.INFO,
          "No configured Kotlin module was found.",
      ))
    }
    return DefaultProjectCreationCapabilities(
        projectRoot = rootProject.rootDir.absolutePath,
        settingsDsl = detectSettingsDsl(rootProject.rootDir),
        applicationProjects = rootProject.allprojects.filter { it.pluginManager.hasPlugin(APP_PLUGIN) }
            .map(Project::getPath).sorted(),
        candidates = candidates.distinctBy(ModuleCreationCandidate::id).sortedBy(ModuleCreationCandidate::id),
        diagnostics = diagnostics,
    )
  }

  private fun candidatesFor(project: Project): List<ModuleCreationCandidate> {
    val dsl = if (project.buildFile.name.endsWith(".kts")) GradleDsl.KOTLIN else GradleDsl.GROOVY
    val style = detectPluginStyle(project.buildFile)
    return buildList {
      if (project.pluginManager.hasPlugin(LIBRARY_PLUGIN)) {
        add(candidate(project, ModuleCreationKind.ANDROID_LIBRARY, ModuleSourceLanguage.JAVA, dsl, style))
        if (project.pluginManager.hasPlugin("org.jetbrains.kotlin.android") ||
            project.pluginManager.hasPlugin("kotlin-android")) {
          add(candidate(project, ModuleCreationKind.ANDROID_LIBRARY, ModuleSourceLanguage.KOTLIN, dsl, style))
        }
      }
      if (project.pluginManager.hasPlugin("java-library")) {
        add(candidate(project, ModuleCreationKind.JAVA_LIBRARY, ModuleSourceLanguage.JAVA, dsl, style))
        if (project.pluginManager.hasPlugin("org.jetbrains.kotlin.jvm") || project.pluginManager.hasPlugin("kotlin")) {
          add(candidate(project, ModuleCreationKind.JAVA_LIBRARY, ModuleSourceLanguage.KOTLIN, dsl, style))
        }
      }
    }
  }

  private fun candidate(project: Project, kind: ModuleCreationKind, language: ModuleSourceLanguage,
      dsl: GradleDsl, style: PluginApplicationStyle): ModuleCreationCandidate {
    val id = "${project.path}|${kind.name}|${language.name}|${dsl.name}|${style.name}"
    return ModuleCreationCandidate(id, kind, language, dsl, style, project.path)
  }

  private fun detectSettingsDsl(rootDir: File) = when {
    File(rootDir, "settings.gradle.kts").isFile -> GradleDsl.KOTLIN
    File(rootDir, "settings.gradle").isFile -> GradleDsl.GROOVY
    else -> GradleDsl.UNKNOWN
  }

  private fun detectPluginStyle(buildFile: File): PluginApplicationStyle {
    val content = runCatching(buildFile::readText).getOrDefault("")
    return when {
      Regex("(?m)^\\s*plugins\\s*\\{").containsMatchIn(content) -> PluginApplicationStyle.PLUGINS_BLOCK
      content.contains("apply plugin:") || content.contains("apply(plugin") -> PluginApplicationStyle.LEGACY_APPLY
      else -> PluginApplicationStyle.UNKNOWN
    }
  }
}