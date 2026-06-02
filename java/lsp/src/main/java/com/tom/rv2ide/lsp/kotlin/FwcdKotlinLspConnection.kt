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
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process launcher for the fwcd/kotlin-language-server backend.
 *
 * Recommended private layout:
 * - runtime bundle root: `${Environment.SERVERS_KOTLIN_DIR}/fwcd`
 * - process HOME: [Environment.HOME]
 * - XDG config/cache roots: `${Environment.HOME}/.config` and `${Environment.HOME}/.cache`
 *
 * This backend reuses [BaseStdioKotlinLspConnection], while executable discovery
 * and process command construction differ from the bundled javacs backend.
 */
class FwcdKotlinLspConnection : BaseStdioKotlinLspConnection() {
  private val manifestPathCandidates =
      listOf(
          File(Environment.HOME, "acs/docs/misc/language-server-manifest.sample.json"),
          File(Environment.HOME, "docs/misc/language-server-manifest.sample.json"),
      )

  override fun startProcess(classpathProvider: KotlinClasspathProvider): Process? {
    val serverHome = runtimeDir()
    if (!serverHome.exists()) {
      KslLogs.error(
          "FWCD Kotlin language server bundle not found at: {}",
          serverHome.absolutePath,
      )
      return null
    }

    val launcher = findLauncher(serverHome)
    if (launcher == null) {
      KslLogs.error(
          "FWCD Kotlin language server launcher not found under: {}",
          serverHome.absolutePath,
      )
      return null
    }

    val xdgConfigHome = ensureDir(File(Environment.HOME, ".config"))
    val xdgCacheHome = ensureDir(File(Environment.HOME, ".cache"))
    val sqliteNativeConfig = prepareSqliteNativeConfig(serverHome)

    val command =
        buildDirectJavaCommand(launcher, serverHome, classpathProvider, sqliteNativeConfig)
            ?: buildLauncherCommand(launcher)

    return try {
      ProcessBuilder(command).apply {
            redirectErrorStream(false)
            directory(serverHome)
            environment().apply {
              put("HOME", Environment.HOME.absolutePath)

              val javaHome = Environment.JAVA_HOME?.absolutePath ?: Environment.PREFIX.absolutePath
              put("JAVA_HOME", javaHome)

              val javaBinPath = File(javaHome, "bin").absolutePath
              val currentPath = get("PATH") ?: ""
              put("PATH", if (currentPath.isBlank()) javaBinPath else "$javaBinPath:$currentPath")

              put("XDG_CONFIG_HOME", xdgConfigHome.absolutePath)
              put("XDG_CACHE_HOME", xdgCacheHome.absolutePath)

              val androidClasspath = classpathProvider.getClasspath()
              put("KOTLIN_LSP_CLASSPATH", androidClasspath)
              put("CLASSPATH", androidClasspath)

              val sdkPath = classpathProvider.getAndroidSdkPath()
              if (sdkPath.isNotEmpty()) {
                put("ANDROID_SDK_ROOT", sdkPath)
                put("ANDROID_HOME", sdkPath)
              }
            }
          }
          .start()
          .also {
            KslLogs.info(
                "Started FWCD Kotlin language server (launcher={}, runtimeDir={})",
                launcher.absolutePath,
                serverHome.absolutePath,
            )
          }
    } catch (e: Exception) {
      KslLogs.error("Failed to start FWCD Kotlin language server", e)
      null
    }
  }

  override fun logPrefix(): String = "FWCD Kotlin LSP"

  override fun onProcessStartFailed(error: Exception) {
    KslLogs.error("Failed to start fwcd/kotlin-language-server backend process", error)
  }

  private fun runtimeDir(): File {
    val manifestItem = loadManifestServerItem()
    val install = manifestItem?.optJSONObject("install")
    val targetRelativeTo = install?.optString("targetRelativeTo")?.trim()
    val targetSubdir = install?.optString("targetSubdir")?.trim().orEmpty()

    val baseDir = when (targetRelativeTo) {
      "SERVERS_KOTLIN_DIR" -> Environment.SERVERS_KOTLIN_DIR
      "SERVERS_DIR" -> Environment.SERVERS_DIR
      "HOME" -> Environment.HOME
      else -> Environment.SERVERS_KOTLIN_DIR
    }

    return if (targetSubdir.isNotEmpty()) File(baseDir, targetSubdir) else File(baseDir, "fwcd")
  }

  private fun ensureDir(dir: File): File {
    if (!dir.exists()) {
      dir.mkdirs()
    }
    return dir
  }

  private fun buildDirectJavaCommand(
      launcher: File,
      serverHome: File,
      classpathProvider: KotlinClasspathProvider,
      sqliteNativeConfig: SqliteNativeConfig?,
  ): List<String>? {
    val appHome = launcher.parentFile?.parentFile ?: serverHome
    val libDir = File(appHome, "lib")
    val jars = libDir.listFiles { file -> file.isFile && file.name.endsWith(".jar") }

    if (jars.isNullOrEmpty()) {
      KslLogs.warn(
          "FWCD lib jars not found under {}, falling back to launcher script",
          libDir.absolutePath,
      )
      return null
    }
    val javaExec = Environment.JAVA.absolutePath
    val serverClasspath = jars.joinToString(":") { it.absolutePath }
    val androidClasspath = classpathProvider.getClasspath()

    val fwcdVersion = detectFwcdVersion(jars) ?: "1.3.13"

    KslLogs.info(
        "Starting FWCD Kotlin language server with direct java launcher: appHome={}, jars={}, version={}",
        appHome.absolutePath,
        jars.size,
        fwcdVersion,
    )
    val command =
        mutableListOf(
            javaExec,
            "-XX:+UseG1GC",
            "-XX:+UseStringDeduplication",
            "-XX:+OptimizeStringConcat",
            "-XX:+TieredCompilation",
            "-XX:TieredStopAtLevel=1",
            "-Djava.awt.headless=true",
            "-DkotlinLanguageServer.version=$fwcdVersion",
            "-DkotlinLanguageServer.skipClasspathResolution=true",
            "-DkotlinLanguageServer.predefinedClasspath=$androidClasspath",
        )

    if (sqliteNativeConfig != null) {
      command += "-Dorg.sqlite.lib.path=${sqliteNativeConfig.libPath}"
      command += "-Dorg.sqlite.lib.name=${sqliteNativeConfig.libName}"
      KslLogs.debug(
          "Prepared Android sqlite native library for FWCD: dir={}, name={}",
          sqliteNativeConfig.libPath,
          sqliteNativeConfig.libName,
      )
    } else {
      KslLogs.warn("Android sqlite native override not prepared; FWCD will use bundled sqlite-jdbc defaults")
    }

    command += listOf("-classpath", serverClasspath, "org.javacs.kt.MainKt")
    return command


  }

  private fun detectFwcdVersion(jars: Array<File>): String? {
    return jars
        .asSequence()
        .map { it.name }
        .firstOrNull { it.startsWith("server-") && it.endsWith(".jar") }
        ?.removePrefix("server-")
        ?.removeSuffix(".jar")
        ?.takeIf { it.isNotBlank() }
  }

  private fun prepareSqliteNativeConfig(serverHome: File): SqliteNativeConfig? {
    val sqliteJar =
        listOf(
                File(serverHome, "server/lib/sqlite-jdbc-3.41.2.1.jar"),
                File(serverHome, "lib/sqlite-jdbc-3.41.2.1.jar"),
            )
            .firstOrNull { it.exists() && it.isFile }
    if (sqliteJar == null) {
      KslLogs.warn("sqlite-jdbc jar not found for FWCD under {}", serverHome.absolutePath)
      return null
    }

    val nativeDir = ensureDir(File(Environment.HOME, ".cache/fwcd/sqlite-android-aarch64"))
    val nativeName = "libsqlitejdbc.so"
    val nativeFile = File(nativeDir, nativeName)
    val resourcePath = "org/sqlite/native/Linux-Android/aarch64/$nativeName"

    return try {
      ZipFile(sqliteJar).use { zip ->
        val entry = zip.getEntry(resourcePath)
        if (entry == null) {
          KslLogs.warn("Android sqlite native entry not found in {}: {}", sqliteJar.absolutePath, resourcePath)
          return null
        }

        zip.getInputStream(entry).use { input ->
          nativeFile.outputStream().use { output ->
            input.copyTo(output)
          }
        }
      }

      nativeFile.setReadable(true, false)
      nativeFile.setWritable(true, true)
      nativeFile.setExecutable(true, false)
      SqliteNativeConfig(nativeDir.absolutePath, nativeName)
    } catch (e: Exception) {
      KslLogs.warn("Failed to prepare Android sqlite native library for FWCD", e)
      null
    }
  }

  private data class SqliteNativeConfig(
      val libPath: String,
      val libName: String,
  )

  private fun buildLauncherCommand(launcher: File): List<String> {
    return when {
      launcher.name.endsWith(".sh") -> listOf("/system/bin/sh", launcher.absolutePath)
      launcher.canExecute() -> listOf(launcher.absolutePath)
      else -> listOf("/system/bin/sh", launcher.absolutePath)
    }
  }

  private fun findLauncher(serverHome: File): File? {
    val manifestCandidates =
        loadManifestServerItem()
            ?.optJSONObject("runtime")
            ?.optJSONArray("launcherCandidates")
            .toRelativePaths()
            .map { File(serverHome, it) }
            .orEmpty()

    val directCandidates =
        manifestCandidates +
            listOf(
                File(serverHome, "bin/kotlin-language-server"),
                File(serverHome, "bin/kotlin-language-server.sh"),
                File(serverHome, "kotlin-language-server"),
                File(serverHome, "kotlin-language-server.sh"),
                File(serverHome, "server/bin/kotlin-language-server"),
                File(serverHome, "server/bin/kotlin-language-server.sh"),
            )

    return directCandidates.firstOrNull { it.exists() && it.isFile }
  }

  private fun loadManifestServerItem(): JSONObject? {
    return try {
      val manifestPath = manifestPathCandidates.firstOrNull { it.exists() && it.isFile } ?: return null
      val manifest = JSONObject(manifestPath.readText())
      selectManifestServerItem(manifest)
    } catch (e: Exception) {
      KslLogs.warn("Failed to read manifest launcher metadata", e)
      null
    }
  }

  private fun selectManifestServerItem(manifest: JSONObject): JSONObject? {
    return findManifestServerItem(manifest.optJSONArray("servers"), "fwcd")
        ?: findManifestServerItem(manifest.optJSONArray("Servers"), "fwcd")
  }

  private fun findManifestServerItem(array: JSONArray?, serverId: String): JSONObject? {
    if (array == null) return null

    val normalizedId = serverId.trim().lowercase()
    var firstKotlin: JSONObject? = null
    var firstItem: JSONObject? = null

    for (i in 0 until array.length()) {
      val item = array.optJSONObject(i) ?: continue
      if (firstItem == null) firstItem = item

      val id = item.optString("id").trim().lowercase()
      val language = item.optString("language", "kotlin").trim().lowercase()
      val backend = item.optString("backend").trim().lowercase()

      if (language == "kotlin" && firstKotlin == null) {
        firstKotlin = item
      }
      if (language == "kotlin" && (id == normalizedId || backend == normalizedId)) {
        return item
      }
    }

    return firstKotlin ?: firstItem
  }

  private fun JSONArray?.toRelativePaths(): List<String> {
    if (this == null) return emptyList()
    val result = ArrayList<String>(length())
    for (i in 0 until length()) {
      val value = optString(i).trim()
      if (value.isNotEmpty() && value != "null") {
        result.add(value)
      }
    }
    return result
  }
}
