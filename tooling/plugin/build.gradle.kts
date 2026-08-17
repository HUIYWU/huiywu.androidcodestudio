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

import com.tom.rv2ide.build.config.AGP_VERSION_MINIMUM
import com.tom.rv2ide.build.config.BuildConfig
import com.tom.rv2ide.build.config.ProjectConfig

plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("java-gradle-plugin")
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
}

description = "Gradle Plugin for projects that are built with AndroidCS"

val capabilityInitRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(projects.tooling.model)
    implementation(projects.tooling.pluginConfig)
    implementation(projects.utilities.buildInfo)

    capabilityInitRuntime(projects.tooling.model)
    capabilityInitRuntime(projects.tooling.pluginConfig)
    capabilityInitRuntime(projects.utilities.buildInfo)

    // AGP included in output JAR
    implementation("com.android.tools.build:gradle:${AGP_VERSION_MINIMUM}")
}

gradlePlugin {
    website.set(ProjectConfig.REPO_URL)
    vcsUrl.set(ProjectConfig.REPO_URL)

    plugins {
        create("gradlePlugin") {
            id = BuildConfig.packageName
            implementationClass = "${BuildConfig.packageName}.gradle.AndroidIDEGradlePlugin"
        }
        create("logsenderPlugin") {
          id = "${BuildConfig.packageName}.logsender"
          implementationClass = "${BuildConfig.packageName}.gradle.LogSenderPlugin"
          displayName = "AndroidIDE LogSender Gradle Plugin"
          description = "Gradle plugin for applying LogSender-specific configuration to projects that are built with AndroidIDE"
          tags.set(setOf("androidide", "logsender"))
        }
    }
    
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("androidide-plugin")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("androidide-capability-init")
    archiveClassifier.set("")
    configurations = listOf(capabilityInitRuntime)
    // Gradle supplies its API and implementation classes to init scripts. Keep AGP out of this
    // capability-only artifact; the builder only checks plugin ids and does not link AGP APIs.
}