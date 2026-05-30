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
package com.tom.rv2ide.lsp.kotlin

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tom.rv2ide.utils.Environment
import java.io.File

/**
 * Default backend configurator for the bundled org.javacs.kt-based server.
 */
object JavacsKotlinLspBackendConfigurator : KotlinLspBackendConfigurator {
  override fun beforeServerStart(
      processManager: KotlinLspConnection,
      classpathProvider: KotlinClasspathProvider,
  ) {
    createKlsClasspathScript(classpathProvider)
  }

  override fun afterServerInitialized(
      processManager: KotlinLspConnection,
      classpathProvider: KotlinClasspathProvider,
  ) {
    sendScriptConfiguration(processManager, classpathProvider)
  }

  private fun sendScriptConfiguration(
      processManager: KotlinLspConnection,
      classpathProvider: KotlinClasspathProvider,
  ) {
    KslLogs.info("Sending script configuration...")

    val scriptConfigParams =
        JsonObject().apply {
          add(
              "settings",
              JsonObject().apply {
                add(
                    "kotlin",
                    JsonObject().apply {
                      add(
                          "scripts",
                          JsonObject().apply {
                            addProperty("enabled", false)
                            addProperty("buildScriptsEnabled", false)
                            add(
                                "templates",
                                JsonArray().apply {
                                  add("kotlin.script.templates.standard.ScriptTemplateWithArgs")
                                },
                            )
                            val classpathList = classpathProvider.getClasspathList()
                            add(
                                "classpath",
                                JsonArray().apply {
                                  classpathList.forEach { path -> add(path) }
                                },
                            )
                          },
                      )
                    },
                )
              },
          )
        }

    processManager.sendNotification("workspace/didChangeConfiguration", scriptConfigParams)
    KslLogs.info(
        "Script configuration sent with {} classpath entries",
        classpathProvider.getClasspathList().size,
    )
  }

  private fun findJavaPath(): String {
    val candidates =
        listOf(
            "/data/data/com.tom.rv2ide/files/usr/bin/java",
            "/data/data/com.tom.rv2ide/files/usr/opt/openjdk/bin/java",
            System.getenv("JAVA_HOME")?.let { "$it/bin/java" },
        )

    val foundPath = candidates.filterNotNull().firstOrNull { path -> File(path).exists() }

    if (foundPath != null) {
      KslLogs.info("Found Java at: {}", foundPath)
      return foundPath
    }

    KslLogs.warn("Java not found in standard locations, using default")
    return "/data/data/com.tom.rv2ide/files/usr/bin/java"
  }

  private fun createKlsClasspathScript(classpathProvider: KotlinClasspathProvider) {
    try {
      val classpathScript = File(Environment.SERVER_CONFIG_DIR, "classpath")
      val androidClasspath = classpathProvider.getClasspath()
      val androidSdkPath = classpathProvider.getAndroidSdkPath()

      val javaPath = findJavaPath()
      val javaHome =
          File(javaPath).parentFile?.parentFile?.absolutePath
              ?: "/data/data/com.tom.rv2ide/files/usr"

      val javaBinPath = File(javaPath).parent ?: "/data/data/com.tom.rv2ide/files/usr/bin"

      val scriptContent =
          """#!/system/bin/sh
# kls-classpath script for Kotlin Language Server
# This script provides Android classpath and Java environment

# Set Java home and path
export JAVA_HOME=\"${javaHome}\"
export PATH=\"${javaBinPath}:${'$'}PATH\"

# Set Android SDK path
export ANDROID_SDK_ROOT=\"${androidSdkPath}\"
export ANDROID_HOME=\"${androidSdkPath}\"

# Disable Gradle dependency resolution
export KOTLIN_LSP_DISABLE_DEPENDENCY_RESOLUTION=true
export KOTLIN_LSP_USE_PREDEFINED_CLASSPATH=true

# Output the classpath (already includes everything from build dirs)
echo \"${androidClasspath}\"
"""
              .trimIndent()

      classpathScript.writeText(scriptContent)
      classpathScript.setExecutable(true, false)

      try {
        Runtime.getRuntime().exec(arrayOf("chmod", "755", classpathScript.absolutePath)).waitFor()
      } catch (e: Exception) {
        KslLogs.debug("chmod command not available, relying on setExecutable")
      }

      KslLogs.info(
          "Created kls-classpath script with {} entries",
          classpathProvider.getClasspathList().size,
      )
    } catch (e: Exception) {
      KslLogs.error("Failed to create kls-classpath script", e)
    }
  }
}
