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
package com.tom.rv2ide.lsp.clang

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.utils.Environment
import java.io.File
import org.slf4j.LoggerFactory

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class ClangWorkspaceSetup(private val context: Context, private val workspace: IWorkspace) {

  companion object {
    private val log = LoggerFactory.getLogger(ClangWorkspaceSetup::class.java)
    private val CPP_EXTENSIONS = setOf("cpp", "cc", "cxx", "c++")
    private val C_EXTENSIONS = setOf("c")
    private val SOURCE_EXTENSIONS = CPP_EXTENSIONS + C_EXTENSIONS
    private val INCLUDE_DIR_NAMES = setOf("include", "includes", "inc", "src", "cpp", "cxx")
    private val EXCLUDED_DIR_NAMES =
        setOf(
            "build",
            ".gradle",
            ".git",
            ".idea",
            ".cxx",
            ".externalNativeBuild",
            "node_modules",
        )
  }
  fun setup(processManager: ClangServerProcessManager): Boolean {
    val workspaceRoot = workspace.getProjectDir().toURI().toString()
    ClangLogs.info("Setting up clang workspace: {}", workspaceRoot)

    return try {
      val compileCommandsPath = generateCompileCommands()

      if (compileCommandsPath == null) {
        ClangLogs.warn("compile_commands dir is null; clangd will start without --compile-commands-dir")
      }

      processManager.startServer(compileCommandsPath)

      val initParams = createInitParams(workspaceRoot, compileCommandsPath)

      processManager.sendRequest("initialize", initParams).get()
      ClangLogs.info("Clang workspace initialized successfully")
      processManager.sendNotification("initialized", JsonObject())
      true
    } catch (e: Exception) {
      ClangLogs.error("Clang workspace setup failed during initialize", e)
      false
    }
  }


  private fun generateCompileCommands(): String? {
    try {
      val projectDir = workspace.getProjectDir()
      findExistingCompileCommands(projectDir)?.let { existing ->
        return existing.parentFile?.absolutePath
      }

      val buildDir =
          File(Environment.PREFIX, "clanglsp/build").apply {
            deleteRecursively()
            mkdirs()
          }
      val compileCommandsFile = File(buildDir, "compile_commands.json")


      val sourceFiles = findSourceFiles(projectDir)

      if (sourceFiles.isEmpty()) {
        ClangLogs.warn("No C/C++ source files found in project")
        return null
      }
      val projectIncludeDirs = findProjectIncludeDirs(projectDir)

      val jsonArray = JsonArray()

      sourceFiles.forEach { file ->
        val entry =
            JsonObject().apply {
              addProperty("directory", projectDir.absolutePath)
              addProperty("command", buildClangCommand(file, projectIncludeDirs))
              addProperty("file", file.absolutePath)
            }
        jsonArray.add(entry)
      }
      compileCommandsFile.writeText(jsonArray.toString())

      return buildDir.absolutePath

    } catch (e: Exception) {
      ClangLogs.error("Failed to generate compile_commands.json", e)
      return null
    }
  }

  private fun findExistingCompileCommands(projectDir: File): File? {
    val direct = File(projectDir, "compile_commands.json")
    if (direct.isFile) {
      return direct
    }

    val commonCandidates =
        listOf(
            File(projectDir, "build/compile_commands.json"),
            File(projectDir, ".cxx/compile_commands.json"),
            File(projectDir, ".externalNativeBuild/compile_commands.json"),
            File(projectDir, "app/compile_commands.json"),
        )

    commonCandidates.firstOrNull { it.isFile }?.let { return it }

    return findFileByName(projectDir, "compile_commands.json", maxDepth = 6)
  }

  private fun findFileByName(
      dir: File,
      name: String,
      maxDepth: Int,
      currentDepth: Int = 0,
  ): File? {
    if (currentDepth > maxDepth || !dir.isDirectory || dir.name in EXCLUDED_DIR_NAMES) {
      return null
    }

    dir.listFiles()?.forEach { file ->
      when {
        file.isFile && file.name == name -> return file
        file.isDirectory && !file.name.startsWith(".") -> {
          findFileByName(file, name, maxDepth, currentDepth + 1)?.let { return it }
        }
      }
    }

    return null
  }

  private fun findSourceFiles(dir: File, maxDepth: Int = 6, currentDepth: Int = 0): List<File> {
    if (currentDepth > maxDepth || !dir.isDirectory || dir.name in EXCLUDED_DIR_NAMES) {
      return emptyList()
    }

    val sourceFiles = mutableListOf<File>()

    dir.listFiles()?.forEach { file ->
      when {
        file.isFile && file.extension.lowercase() in SOURCE_EXTENSIONS -> {
          sourceFiles.add(file)
        }
        file.isDirectory && !file.name.startsWith(".") -> {
          sourceFiles.addAll(findSourceFiles(file, maxDepth, currentDepth + 1))
        }
      }
    }

    return sourceFiles
  }

  private fun findProjectIncludeDirs(
      dir: File,
      maxDepth: Int = 6,
      currentDepth: Int = 0,
  ): List<File> {
    if (currentDepth > maxDepth || !dir.isDirectory || dir.name in EXCLUDED_DIR_NAMES) {
      return emptyList()
    }

    val result = linkedSetOf<File>()
    val children = dir.listFiles() ?: return emptyList()

    val hasHeader =
        children.any {
          it.isFile && it.extension.lowercase() in setOf("h", "hpp", "hh", "hxx")
        }
    val hasSource = children.any { it.isFile && it.extension.lowercase() in SOURCE_EXTENSIONS }

    if (dir.name.lowercase() in INCLUDE_DIR_NAMES || hasHeader || hasSource) {
      result.add(dir)
    }

    children.forEach { file ->
      if (file.isDirectory && !file.name.startsWith(".")) {
        result.addAll(findProjectIncludeDirs(file, maxDepth, currentDepth + 1))
      }
    }

    return result.sortedBy { it.absolutePath.length }
  }

  private fun buildClangCommand(file: File, projectIncludeDirs: List<File>): String {
    val clangPath = File(Environment.PREFIX, "bin/clang++").absolutePath
    val flags = mutableListOf<String>()
    flags.add("\"$clangPath\"")
    flags.addAll(buildCommonFlags(file, projectIncludeDirs, forFallback = false))
    flags.add("-c")
    flags.add("\"${file.absolutePath}\"")
    return flags.joinToString(" ")
  }

  private fun buildCommonFlags(
      file: File?,
      projectIncludeDirs: List<File>,
      forFallback: Boolean,
  ): List<String> {
    val flags = mutableListOf<String>()
    flags.add(languageStandardFlag(file))

    systemIncludePaths().forEach { path ->
      flags.add("-isystem")
      flags.add(if (forFallback) path else "\"$path\"")
    }

    projectIncludeDirs.forEach { dir ->
      flags.add("-I")
      flags.add(if (forFallback) dir.absolutePath else "\"${dir.absolutePath}\"")
    }

    flags.add("-resource-dir=${File(Environment.PREFIX, "lib/clang/20").absolutePath}")
    flags.add("-target")
    flags.add(detectTargetTriple())
    flags.add("-D__ANDROID__")
    flags.add("-DANDROID")
    flags.add("-D__TERMUX__")
    flags.add("-Wno-unused-parameter")
    flags.add("-Wno-unused-variable")
    flags.add("-Wno-unknown-pragmas")
    return flags
  }

  private fun languageStandardFlag(file: File?): String {
    val extension = file?.extension?.lowercase()
    return if (extension in CPP_EXTENSIONS || extension == null) "-std=c++17" else "-std=c11"
  }
  private fun systemIncludePaths(): List<String> {
    val includePaths =
        listOf(
            File(Environment.PREFIX, "include/c++/v1").absolutePath,
            File(Environment.PREFIX, "lib/clang/20/include").absolutePath,
            File(Environment.PREFIX, "include/aarch64-linux-android").absolutePath,
            File(Environment.PREFIX, "include").absolutePath,
        )

    return includePaths.filter { File(it).exists() }
  }


  private fun detectTargetTriple(): String {
    return "aarch64-unknown-linux-android24"
  }
  private fun createInitParams(workspaceRoot: String, compilationDatabasePath: String?): JsonObject {
    val projectIncludeDirs = findProjectIncludeDirs(workspace.getProjectDir())
    val fallbackFlags =
        buildCommonFlags(
            file = null,
            projectIncludeDirs = projectIncludeDirs,
            forFallback = true,
        )

    return JsonObject().apply {

      addProperty("processId", android.os.Process.myPid())
      addProperty("rootUri", workspaceRoot)

      add(
          "capabilities",
          JsonObject().apply {
            add(
                "textDocument",
                JsonObject().apply {
                  add(
                      "completion",
                      JsonObject().apply {
                        add(
                            "completionItem",
                            JsonObject().apply {
                              addProperty("snippetSupport", true)
                              addProperty("commitCharactersSupport", true)
                              add(
                                  "documentationFormat",
                                  JsonArray().apply {
                                    add("plaintext")
                                    add("markdown")
                                  },
                              )
                              addProperty("deprecatedSupport", true)
                              addProperty("preselectSupport", true)
                            },
                        )
                        addProperty("contextSupport", true)
                      },
                  )
                  add(
                      "hover",
                      JsonObject().apply {
                        add(
                            "contentFormat",
                            JsonArray().apply {
                              add("plaintext")
                              add("markdown")
                            },
                        )
                      },
                  )
                  add(
                      "signatureHelp",
                      JsonObject().apply {
                        add(
                            "signatureInformation",
                            JsonObject().apply {
                              add(
                                  "documentationFormat",
                                  JsonArray().apply {
                                    add("plaintext")
                                    add("markdown")
                                  },
                              )
                            },
                        )
                      },
                  )
                  add("definition", JsonObject().apply { addProperty("linkSupport", true) })
                  add("references", JsonObject())
                  add("documentHighlight", JsonObject())
                  add("documentSymbol", JsonObject())
                  add("codeAction", JsonObject())
                  add("codeLens", JsonObject())
                  add("formatting", JsonObject())
                  add("rangeFormatting", JsonObject())
                  add("onTypeFormatting", JsonObject())
                  add("rename", JsonObject())
                  add(
                      "publishDiagnostics",
                      JsonObject().apply { addProperty("relatedInformation", true) },
                  )
                },
            )

            add(
                "workspace",
                JsonObject().apply {
                  addProperty("applyEdit", true)
                  add("workspaceEdit", JsonObject().apply { addProperty("documentChanges", true) })
                  add(
                      "didChangeConfiguration",
                      JsonObject().apply { addProperty("dynamicRegistration", true) },
                  )
                  add(
                      "didChangeWatchedFiles",
                      JsonObject().apply { addProperty("dynamicRegistration", true) },
                  )
                  add("symbol", JsonObject().apply { addProperty("dynamicRegistration", true) })
                  add(
                      "executeCommand",
                      JsonObject().apply { addProperty("dynamicRegistration", true) },
                  )
                },
            )
          },
      )

      add(
          "initializationOptions",
          JsonObject().apply {
            addProperty("clangdFileStatus", true)
            addProperty("compilationDatabasePath", compilationDatabasePath ?: "${Environment.PREFIX}/clanglsp/build")

            add(
                "fallbackFlags",
                JsonArray().apply {
                  fallbackFlags.forEach { add(it) }
                },
            )

          },
      )
    }
  }
}
