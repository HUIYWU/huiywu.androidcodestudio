/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.tooling.impl.sync

import com.android.builder.model.v2.ide.GraphItem
import com.android.builder.model.v2.ide.Library
import com.android.builder.model.v2.models.AndroidDsl
import com.android.builder.model.v2.models.AndroidProject
import com.android.builder.model.v2.models.BasicAndroidProject
import com.android.builder.model.v2.models.ModelBuilderParameter
import com.android.builder.model.v2.models.ProjectSyncIssues
import com.android.builder.model.v2.models.VariantDependencies
import com.tom.rv2ide.tooling.api.IAndroidProject
import com.tom.rv2ide.tooling.api.messages.InitializeProjectParams
import com.tom.rv2ide.tooling.impl.internal.AndroidProjectImpl
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency

/**
 * Builds model for Android application and library projects.
 *
 * @author Akash Yadav
 * @modification Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 * ++ AGP 8.13.0: Updated to use additionalArtifactsInModel for proper dependency resolution
 */
class AndroidProjectModelBuilder(initializationParams: InitializeProjectParams) :
    AbstractModelBuilder<AndroidProjectModelBuilderParams, IAndroidProject>(initializationParams) {

  override fun build(param: AndroidProjectModelBuilderParams): IAndroidProject {
    val (controller, module, versions, syncIssueReporter) = param

    val androidParams = initializationParams.androidParams
    val projectPath = module.gradleProject.path
    val basicModel = controller.getModelAndLog(module, BasicAndroidProject::class.java)
    val androidModel = controller.getModelAndLog(module, AndroidProject::class.java)
    val androidDsl = controller.getModelAndLog(module, AndroidDsl::class.java)

    val variantNames = basicModel.variants.map { it.name }
    log("${variantNames.size} build variants found for project '$projectPath': $variantNames")

    var androidVariant = androidParams.variantSelections[projectPath]

    if (androidVariant != null && !variantNames.contains(androidVariant)) {
      log(
          "Configured variant '$androidVariant' not found for project '$projectPath'. Falling back to default variant."
      )
      androidVariant = null
    }

    val configurationVariant = androidVariant ?: variantNames.firstOrNull()
    if (configurationVariant.isNullOrBlank()) {
      throw ModelBuilderException(
          "No variant found for project '$projectPath'. providedVariant=$androidVariant"
      )
    }

    log("Selected build variant '$configurationVariant' for project '$projectPath'")

    try {
      log("Forcing dependency resolution for Android module: $projectPath")
      var downloadedCount = 0
      for (dependency in module.dependencies) {
        if (dependency is IdeaSingleEntryLibraryDependency) {
          try {
            val file = dependency.file
            if (file.exists()) {
              downloadedCount++
            }
          } catch (fileEx: Exception) {
            log("Failed to access dependency file: ${fileEx.message}")
          }
        }
      }
      log("Forced resolution of $downloadedCount dependencies for module: $projectPath")
    } catch (resEx: Exception) {
      log("Failed to pre-resolve dependencies: ${resEx.message}")
    }

    val variantDependencies =
        controller.getModelAndLog(
            module,
            VariantDependencies::class.java,
            ModelBuilderParameter::class.java,
        ) {
          it.variantName = configurationVariant
          it.additionalArtifactsInModel =
              true
          it.dontBuildRuntimeClasspath =
              false
          it.dontBuildUnitTestRuntimeClasspath = true
          it.dontBuildScreenshotTestRuntimeClasspath = true
          it.dontBuildAndroidTestRuntimeClasspath = true
          it.dontBuildTestFixtureRuntimeClasspath = true
          it.dontBuildHostTestRuntimeClasspath = emptyMap()
        }

    logComposeDependencySummary(projectPath, configurationVariant, variantDependencies)

    controller.findModel(module, ProjectSyncIssues::class.java)?.also { syncIssues ->
      syncIssueReporter.reportAll(syncIssues)
    }

    return AndroidProjectImpl(
        module.gradleProject,
        configurationVariant,
        basicModel,
        androidModel,
        variantDependencies,
        versions,
        androidDsl,
    )
  }

  private fun logComposeDependencySummary(
      projectPath: String,
      configurationVariant: String,
      variantDependencies: VariantDependencies,
  ) {
    val compileDependencies = variantDependencies.mainArtifact.compileDependencies
    val libraries = variantDependencies.libraries
    val dependencyKeys = linkedSetOf<String>()
    compileDependencies.forEach { collectGraphKeys(it, dependencyKeys) }

    val composeLibraryEntries = libraries.entries.filter { (key, library) ->
      isComposeInteresting(key) || isComposeInteresting(describeLibrary(library))
    }

    val composeLibraryKeys = composeLibraryEntries.map { it.key }.toSet()
    val composeReferencedKeys = composeLibraryKeys.filter { dependencyKeys.contains(it) }
    val composeUnreferencedKeys = composeLibraryKeys.filterNot { dependencyKeys.contains(it) }

    log(
        "Compose dependency summary: projectPath=$projectPath, variant=$configurationVariant, mainCompileRoots=${compileDependencies.size}, mainCompileGraphKeys=${dependencyKeys.size}, totalLibraries=${libraries.size}, composeLibraries=${composeLibraryEntries.size}, composeReferenced=${composeReferencedKeys.size}, composeUnreferenced=${composeUnreferencedKeys.size}")

    if (composeLibraryEntries.isNotEmpty()) {
      val preview = composeLibraryEntries
          .take(20)
          .joinToString(" | ") { (key, library) -> "$key => ${describeLibrary(library)}" }
      log("Compose libraries preview: $preview")
    }

    if (composeUnreferencedKeys.isNotEmpty()) {
      log(
          "Compose libraries not referenced by mainArtifact.compileDependencies: ${composeUnreferencedKeys.take(20)}")
    }

    logMaterial3DependencySummary(projectPath, configurationVariant, libraries, dependencyKeys)
  }

  private fun logMaterial3DependencySummary(
      projectPath: String,
      configurationVariant: String,
      libraries: Map<String, Library>,
      dependencyKeys: Set<String>,
  ) {
    val material3Entries = libraries.entries.filter { (key, library) ->
      isMaterial3Interesting(key) || isMaterial3Interesting(describeLibrary(library))
    }
    val referenced = material3Entries.filter { dependencyKeys.contains(it.key) }
    val unreferenced = material3Entries.filterNot { dependencyKeys.contains(it.key) }

    log(
        "Material3 dependency summary: projectPath=$projectPath, variant=$configurationVariant, material3Libraries=${material3Entries.size}, referenced=${referenced.size}, unreferenced=${unreferenced.size}")

    if (material3Entries.isNotEmpty()) {
      val preview = material3Entries
          .take(20)
          .joinToString(" | ") { (key, library) -> "$key => ${describeLibrary(library)}" }
      log("Material3 libraries preview: $preview")
    }

    if (unreferenced.isNotEmpty()) {
      log(
          "Material3 libraries not referenced by mainArtifact.compileDependencies: ${unreferenced.take(20).map { it.key }}")
    }
  }

  private fun collectGraphKeys(item: GraphItem, result: MutableSet<String>) {
    if (!result.add(item.key)) return
    item.dependencies.forEach { dependency -> collectGraphKeys(dependency, result) }
  }

  private fun isComposeInteresting(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    val normalized = value.lowercase()
    return normalized.contains("androidx.compose") ||
        normalized.contains("compose-ui") ||
        normalized.contains("compose.ui") ||
        normalized.contains("material3") ||
        normalized.contains("ui-text") ||
        normalized.contains("ui-graphics") ||
        normalized.contains("foundation") ||
        normalized.contains("runtime")
  }

  private fun isMaterial3Interesting(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    val normalized = value.lowercase()
    return normalized.contains("androidx.compose.material3") || normalized.contains("material3")
  }

  private fun describeLibrary(library: Library): String {
    val info = library.libraryInfo
    val coordinates = listOfNotNull(info?.group, info?.name, info?.version).joinToString(":")
    val compileJarPreview =
        library.androidLibraryData?.compileJarFiles?.take(3)?.joinToString(",") { it.name }
    val artifactName = library.artifact?.name
    return buildString {
      append("type=")
      append(library.type)
      if (coordinates.isNotBlank()) {
        append(", coords=")
        append(coordinates)
      }
      if (!artifactName.isNullOrBlank()) {
        append(", artifact=")
        append(artifactName)
      }
      if (!compileJarPreview.isNullOrBlank()) {
        append(", compileJars=[")
        append(compileJarPreview)
        append("]")
      }
    }
  }
}
