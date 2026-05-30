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

import com.tom.rv2ide.utils.Environment
import java.io.File
import org.slf4j.LoggerFactory

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class KotlinServerProcessManager : BaseStdioKotlinLspConnection() {
  companion object {
    private val log = LoggerFactory.getLogger(KotlinServerProcessManager::class.java)
  }

  override fun startProcess(classpathProvider: KotlinClasspathProvider): Process? {
    KslLogs.info("Starting Kotlin Language Server with standard JSON-RPC...")

    val serverHome = Environment.SERVERS_KOTLIN_DIR
    val libDir = File(serverHome, "lib")

    if (!serverHome.exists() || !libDir.exists()) {
      KslLogs.error("Server not found at: {}", serverHome.absolutePath)
      return null
    }

    val jars = libDir.listFiles { file: File -> file.name.endsWith(".jar") }
    if (jars == null || jars.isEmpty()) {
      KslLogs.error("No JAR files found in: {}", libDir.absolutePath)
      return null
    }

    val classpath = jars.joinToString(":") { jar -> jar.absolutePath }
    val javaExec = findJavaExecutable()
    val androidClasspath = classpathProvider.getClasspath()
    val javaHome = Environment.JAVA_HOME?.absolutePath ?: Environment.PREFIX.absolutePath
    val klsCacheDir = "${Environment.HOME}/klsCacheDir"

    val command =
        listOf(
            javaExec,
            "-XX:+UseG1GC",
            "-XX:+UseStringDeduplication",
            "-XX:+OptimizeStringConcat",
            "-XX:+TieredCompilation",
            "-XX:TieredStopAtLevel=1",
            "-Djava.awt.headless=true",
            "-DkotlinLanguageServer.skipClasspathResolution=true",
            "-DkotlinLanguageServer.predefinedClasspath=$androidClasspath",
            "-classpath",
            classpath,
            "org.javacs.kt.MainKt",
            "--useCacheDir",
            klsCacheDir,
        )

    val processBuilder =
        ProcessBuilder(command).apply {
          redirectErrorStream(false)
          directory(serverHome)
          environment().apply {
            put("JAVA_HOME", javaHome)

            val javaBinPath = File(javaExec).parent ?: ""
            val currentPath = get("PATH") ?: ""
            put("PATH", "$javaBinPath:$currentPath")

            put("KOTLIN_LSP_DISABLE_DEPENDENCY_RESOLUTION", "true")
            put("KOTLIN_LSP_USE_PREDEFINED_CLASSPATH", "true")
            put("KOTLIN_LSP_CLASSPATH", androidClasspath)
            put("CLASSPATH", androidClasspath)

            val sdkPath = classpathProvider.getAndroidSdkPath()
            if (sdkPath.isNotEmpty()) {
              put("ANDROID_SDK_ROOT", sdkPath)
              put("ANDROID_HOME", sdkPath)
            }

            KslLogs.info("Environment: JAVA_HOME={}", javaHome)
            KslLogs.info("Environment: PATH={}", get("PATH"))
          }
        }

    return try {
      processBuilder.start().also {
        KslLogs.info(
            "Server process started successfully with standard JSON-RPC and JAVA_HOME: {}",
            javaHome,
        )
      }
    } catch (e: Exception) {
      KslLogs.error("Failed to start server", e)
      null
    }
  }

  override fun logPrefix(): String = "KLS"

  override fun onProcessStartFailed(error: Exception) {
    KslLogs.error("Failed to start org.javacs.kt backend process", error)
  }

  private fun findJavaExecutable(): String {
    val candidates =
        listOf(
            "/data/data/com.tom.rv2ide/files/usr/bin/java",
            System.getenv("JAVA_HOME")?.let { "$it/bin/java" },
            "java",
        )

    return candidates.filterNotNull().firstOrNull { path ->
      try {
        File(path).exists() || Runtime.getRuntime().exec(arrayOf(path, "-version")).waitFor() == 0
      } catch (e: Exception) {
        false
      }
    } ?: "java"
  }
}
