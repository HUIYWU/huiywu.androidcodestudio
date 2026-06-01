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

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.kotlin.compiler.KotlinCompilerService
import com.tom.rv2ide.lsp.kotlin.etc.LspFeatures
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projectdata.state.lsp.Index
import com.tom.rv2ide.projectdata.logs.LogStream
import java.io.File
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class KotlinWorkspaceSetup(
    private val context: Context,
    private val workspace: IWorkspace,
    private val backendConfigurator: KotlinLspBackendConfigurator,
    private val backendId: KotlinLspBackendId = KotlinLspBackendId.JAVACS,
) {

  companion object {
    private val log = LoggerFactory.getLogger(KotlinWorkspaceSetup::class.java)
  }

  private var compilerService: KotlinCompilerService? = null
  private val classpathProvider = KotlinClasspathProvider()

  // Use project directory path for cache identification
  private val indexCache = KotlinIndexCache(workspace.getProjectDir().absolutePath)

  private var buildWatcher: WatchService? = null
  private var watcherJob: Job? = null
  private val watchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val resolvedWorkspaceRootDir: File by lazy { resolveKlsWorkspaceRootDir() }

  fun setup(processManager: KotlinLspConnection) {
    val workspaceRootDir = resolvedWorkspaceRootDir
    val workspaceRoot = workspaceRootDir.toPath().toUri().toString()
    KslLogs.info(
        "Setting up workspace with root: {} (projectRoot={})",
        workspaceRoot,
        workspace.getProjectDir().absolutePath,
    )
    Index.setIsIndexing(true)
    LogStream.emitLineBlocking("Setting up workspace...")


    LspFeatures.setProcessManager(processManager)
    initializeCompilerService()
    classpathProvider.initialize(compilerService)

    startBuildWatcher(processManager)

    val currentClasspath = classpathProvider.getClasspathList()
    val currentHash = indexCache.computeClasspathHash(currentClasspath)
    val cacheValid = indexCache.isCacheValid(currentHash)

    KslLogs.info("Cache status: {}", if (cacheValid) "VALID" else "INVALID/MISSING")
    KslLogs.info(indexCache.getCacheStats())

    backendConfigurator.beforeServerStart(processManager, classpathProvider)
    processManager.startServer(classpathProvider)

    val initParams = createInitParams(workspaceRoot)
    logInitializeSummary(initParams)

    KslLogs.info("Sending initialize request...")

    processManager.sendRequest("initialize", initParams) { result ->
      KslLogs.info("Server initialized successfully")
      processManager.sendNotification("initialized", JsonObject())

      backendConfigurator.afterServerInitialized(processManager, classpathProvider)

      if (cacheValid) {
        restoreCachedIndex(processManager)
      } else {
        triggerIndexing(processManager, workspaceRoot, currentHash)
      }
    }
  }
  private fun summarizeJsonKeys(obj: JsonObject?): String {
    if (obj == null || obj.entrySet().isEmpty()) return "[]"
    return obj.entrySet().map { it.key }.sorted().joinToString(prefix = "[", postfix = "]")
  }

  private fun summarizeJsonArrayStrings(array: JsonArray?, limit: Int = 5): String {
    if (array == null || array.size() == 0) return "[]"
    val values =
        array.mapNotNull { element ->
          runCatching {
                when {
                  element.isJsonPrimitive -> element.asString
                  element.isJsonObject -> {
                    val obj = element.asJsonObject
                    obj.get("name")?.asString ?: obj.get("uri")?.asString ?: obj.toString()
                  }
                  else -> element.toString()
                }
              }
              .getOrNull()
        }
    if (values.isEmpty()) return "[]"
    val shown = values.take(limit)
    return shown.joinToString(prefix = "[", postfix = if (values.size > limit) ", ...]" else "]")
  }

  private fun logInitializeSummary(params: JsonObject) {
    val workspaceFolders = params.getAsJsonArray("workspaceFolders")
    val initOptions = params.getAsJsonObject("initializationOptions")
    val scripts = initOptions?.getAsJsonObject("scripts")
    val completion = initOptions?.getAsJsonObject("completion")
    val classpathCount = initOptions?.getAsJsonArray("classpath")?.size() ?: 0
    val snippetsEnabled =
        completion
            ?.getAsJsonObject("snippets")
            ?.get("enabled")
            ?.takeIf { !it.isJsonNull }
            ?.asBoolean
    KslLogs.debug(
        "KLS TRACE init.send backend={} rootUri={} rootPath={} workspaceFolders={} initOptionKeys={} classpathCount={} scriptsEnabled={} buildScriptsEnabled={} usePredefinedClasspath={} disableDependencyResolution={} indexing={} externalSources={} snippetsEnabled={} capabilitiesKeys={}",
        backendId.name.lowercase(),
        params.get("rootUri")?.asString ?: "",
        params.get("rootPath")?.asString ?: "",
        summarizeJsonArrayStrings(workspaceFolders),
        summarizeJsonKeys(initOptions),
        classpathCount,
        scripts?.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean,
        scripts?.get("buildScriptsEnabled")?.takeIf { !it.isJsonNull }?.asBoolean,
        initOptions?.get("usePredefinedClasspath")?.takeIf { !it.isJsonNull }?.asBoolean,
        initOptions?.get("disableDependencyResolution")?.takeIf { !it.isJsonNull }?.asBoolean,
        initOptions?.get("indexing")?.takeIf { !it.isJsonNull }?.asString,
        initOptions?.get("externalSources")?.takeIf { !it.isJsonNull }?.asString,
        snippetsEnabled,
        summarizeJsonKeys(params.getAsJsonObject("capabilities")),
    )
  }

  private fun logDidChangeConfigurationSummary(source: String, params: JsonObject) {
    val settings = params.getAsJsonObject("settings")
    val settingsKeys = summarizeJsonKeys(settings)
    KslLogs.debug(
        "KLS TRACE didChangeConfiguration.send backend={} source={} settingsKeys={} settingsEmpty={}",
        backendId.name.lowercase(),
        source,
        settingsKeys,
        settings == null || settings.entrySet().isEmpty(),
    )
  }

  private fun createFwcdRuntimeConfig(indexingEnabled: Boolean): JsonObject {
    return JsonObject().apply {
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
                      },
                  )
                  add(
                      "completion",
                      JsonObject().apply {
                        add(
                            "snippets",
                            JsonObject().apply { addProperty("enabled", true) },
                        )
                      },
                  )
                  add(
                      "indexing",
                      JsonObject().apply { addProperty("enabled", indexingEnabled) },
                  )
                },
            )
          },
      )
    }
  }

  private fun startBuildWatcher(processManager: KotlinLspConnection) {
    try {
      buildWatcher = FileSystems.getDefault().newWatchService()

      // Watch all Android module build directories
      val modulesToWatch = mutableListOf<File>()
      workspace.getSubProjects().filterIsInstance<AndroidModule>().forEach { module ->
        val buildDir = module.buildDir
        if (buildDir.exists()) {
          modulesToWatch.add(buildDir)
        }
      }

      if (modulesToWatch.isEmpty()) {
        KslLogs.warn("No build directories found to watch")
        return
      }

      // Register directories to watch
      val watchKeys = mutableMapOf<WatchKey, File>()
      modulesToWatch.forEach { buildDir ->
        try {
          val generatedDir = File(buildDir, "generated")
          if (generatedDir.exists()) {
            val key =
                generatedDir
                    .toPath()
                    .register(
                        buildWatcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE,
                    )
            watchKeys[key] = generatedDir
            KslLogs.info("Watching for build changes: {}", generatedDir.absolutePath)
          }
        } catch (e: Exception) {
          KslLogs.warn("Failed to watch directory: {}", buildDir.absolutePath, e)
        }
      }

      // Start watcher coroutine
      watcherJob =
          watchScope.launch {
            var lastReloadTime = 250L
            val reloadDebounceMs = 5000L // Wait 5 seconds after last change

            while (isActive) {
              try {
                val key = buildWatcher?.poll(1, TimeUnit.SECONDS) ?: continue

                val events = key.pollEvents()
                if (events.isNotEmpty()) {
                  val now = System.currentTimeMillis()

                  // Log the changes
                  events.forEach { event ->
                    val kind = event.kind()
                    val filename = event.context()
                    KslLogs.debug("Build change detected: {} - {}", kind.name(), filename)
                  }

                  // Debounce: only reload after changes stop for 5 seconds
                  if (now - lastReloadTime > reloadDebounceMs) {
                    delay(reloadDebounceMs)

                    // Double check no new changes came in during delay
                    val checkKey = buildWatcher?.poll(100, TimeUnit.MILLISECONDS)
                    if (checkKey == null) {
                      // No new changes, safe to reload
                      KslLogs.info("Build changes detected, reloading classpath and index...")
                      reloadClasspathAndIndex(processManager)
                      lastReloadTime = System.currentTimeMillis()
                    } else {
                      // New changes came in, reset timer
                      checkKey.reset()
                    }
                  }
                }

                key.reset()
              } catch (e: Exception) {
                if (e is CancellationException) break
                KslLogs.warn("Error in build watcher", e)
              }
            }
          }

      KslLogs.info("Build watcher started successfully")
    } catch (e: Exception) {
      KslLogs.error("Failed to start build watcher", e)
    }
  }

  private suspend fun reloadClasspathAndIndex(processManager: KotlinLspConnection) {
    withContext(Dispatchers.IO) {
      try {
        Index.setIsIndexing(true)  // Set flag when reload starts
        KslLogs.info("=== RELOADING CLASSPATH AND INDEX ===")

        // Invalidate classpath cache
        classpathProvider.invalidateCache()

        // Re-run backend-specific startup preparation with refreshed classpath
        backendConfigurator.beforeServerStart(processManager, classpathProvider)

        // Clear index cache
        indexCache.clearCache()

        // Compute new classpath hash
        val currentClasspath = classpathProvider.getClasspathList()
        val currentHash = indexCache.computeClasspathHash(currentClasspath)

        // Trigger reindexing (this will also manage the Index flag)
        val workspaceRoot = resolvedWorkspaceRootDir.toPath().toUri().toString()
        triggerIndexing(processManager, workspaceRoot, currentHash)

        KslLogs.info("Classpath and index reloaded successfully")
      } catch (e: Exception) {
        KslLogs.error("Failed to reload classpath and index", e)
        Index.setIsIndexing(false)  // Reset flag on error
      }
    }
  }

  private fun restoreCachedIndex(processManager: KotlinLspConnection) {
    KslLogs.info("Restoring index from cache...")
    Index.setIsIndexing(true)  // Set indexing flag when starting cache restoration

    val cachedSymbols = indexCache.loadCache()
    if (cachedSymbols != null && cachedSymbols.size() > 0) {
      // Send cached configuration
      val configParams = createFwcdRuntimeConfig(indexingEnabled = false)

      logDidChangeConfigurationSummary("restoreCachedIndex", configParams)
      processManager.sendNotification("workspace/didChangeConfiguration", configParams)
      KslLogs.info("Cache restored with {} symbols - indexing skipped", cachedSymbols.size())
      Index.setIsIndexing(false)  // Reset flag after cache restoration
    } else {
      // Cache load failed, trigger fresh indexing
      // Index flag will be managed by triggerIndexing
      val currentClasspath = classpathProvider.getClasspathList()
      val currentHash = indexCache.computeClasspathHash(currentClasspath)
      triggerIndexing(processManager, resolvedWorkspaceRootDir.toPath().toUri().toString(), currentHash)
    }
  }

  private fun triggerIndexing(
      processManager: KotlinLspConnection,
      workspaceRoot: String,
      classpathHash: String,
  ) {
    KslLogs.info("Triggering classpath indexing...")
    Index.setIsIndexing(true)  // Set indexing flag when starting

    val configParams = createFwcdRuntimeConfig(indexingEnabled = true)

    logDidChangeConfigurationSummary("triggerIndexing", configParams)
    processManager.sendNotification("workspace/didChangeConfiguration", configParams)

    // Request symbols to warm up and cache the index
    val symbolParams = JsonObject().apply { addProperty("query", "") }

    processManager.sendRequest("workspace/symbol", symbolParams) { result ->
      try {
        val symbols =
            when {
              result == null -> JsonArray()
              result.has("symbols") -> result.getAsJsonArray("symbols") ?: JsonArray()
              result.has("result") -> result.getAsJsonArray("result") ?: JsonArray()
              else -> JsonArray()
            }
        val symbolCount = symbols.size()
        KslLogs.info("Indexing complete, found {} symbols", symbolCount)

        // Save to cache
        if (symbolCount > 0) {
          indexCache.saveCache(symbols, classpathHash)
        }
      } finally {
        // Always reset the flag when indexing completes (success or failure)
        Index.setIsIndexing(false)
      }
    }
  }

  fun cleanup() {
    try {
      // Stop build watcher
      watcherJob?.cancel()
      buildWatcher?.close()
      watchScope.cancel()

      compilerService?.destroy()
      KotlinCompilerProvider.getInstance().destroy()
      com.tom.rv2ide.lsp.kotlin.compiler.KotlinSourceFileManager.clearCache()
    } catch (e: Exception) {
      KslLogs.warn("Error cleaning up compiler service", e)
    }
  }

  // Add method to get cache instance for manual operations
  fun getIndexCache(): KotlinIndexCache = indexCache

  // Add method to manually trigger reload
  fun manualReloadClasspath(processManager: KotlinLspConnection) {
    watchScope.launch { reloadClasspathAndIndex(processManager) }
  }

  private fun initializeCompilerService() {
    try {
      val mainModule = findMainAndroidModule()
      if (mainModule != null) {
        compilerService = KotlinCompilerProvider.get(mainModule)
        KslLogs.info("Initialized compiler service for: {}", mainModule.path)
        Index.setIsIndexing(true)
        LogStream.emitLineBlocking("Initialized compiler service for: ${mainModule.path}")
      } else {
        KslLogs.warn("No Android module found, using default compiler")
        compilerService = KotlinCompilerService.NO_MODULE_COMPILER
      }
    } catch (e: Exception) {
      KslLogs.error("Failed to initialize compiler service", e)
      compilerService = KotlinCompilerService.NO_MODULE_COMPILER
    }
  }
  private fun resolveKlsWorkspaceRootDir(): File {
    val projectRoot = workspace.getProjectDir()
    if (backendId == KotlinLspBackendId.FWCD && projectRoot.exists() && projectRoot.isDirectory) {
      KslLogs.info(
          "Using project root as FWCD workspace root: {}",
          projectRoot.absolutePath,
      )
      return projectRoot
    }

    val mainModule = findMainAndroidModule()
    if (mainModule != null) {
      val moduleDir = mainModule.projectDir
      if (moduleDir.exists() && moduleDir.isDirectory) {
        if (backendId == KotlinLspBackendId.FWCD) {
          KslLogs.info(
              "Using main Android module as FWCD workspace root: {} (gradlePath={})",
              moduleDir.absolutePath,
              mainModule.path,
          )
          return moduleDir
        }

        // IMPORTANT: Do NOT use the module dir directly as the KLS workspace root for the
        // legacy javacs backend.
        //
        // That backend can enumerate <module>/build.gradle.kts into its source path and poison
        // diagnostics for normal Kotlin sources. Keep the historical src-dir workaround there,
        // but do not apply it to fwcd.
        val srcDir = File(moduleDir, "src")
        if (srcDir.exists() && srcDir.isDirectory) {
          KslLogs.info(
              "Using main Android module src dir as legacy KLS workspace root: {} (module={}, gradlePath={}, backend={})",
              srcDir.absolutePath,
              moduleDir.absolutePath,
              mainModule.path,
              backendId.name.lowercase(),
          )
          return srcDir
        }

        KslLogs.info(
            "Using main Android module as KLS workspace root: {} (gradlePath={}, backend={}, no src dir found)",
            moduleDir.absolutePath,
            mainModule.path,
            backendId.name.lowercase(),
        )
        return moduleDir
      }

      KslLogs.warn(
          "Main Android module directory is invalid, fallback to project root: dir={}, gradlePath={}, backend={}",
          moduleDir.absolutePath,
          mainModule.path,
          backendId.name.lowercase(),
      )
    }

    return workspace.getProjectDir()
  }

  private fun findMainAndroidModule(): ModuleProject? {
    val subProjects = workspace.getSubProjects()

    for (subProject in subProjects) {
      if (subProject is AndroidModule && subProject.isApplication) {
        return subProject
      }
    }

    for (subProject in subProjects) {
      if (subProject is AndroidModule) {
        return subProject
      }
    }

    return null
  }

  private fun createInitParams(workspaceRoot: String): JsonObject {
    KslLogs.info("=== CREATING INIT PARAMS ===")
    val workspaceRootPath = try {
      File(java.net.URI(workspaceRoot)).absolutePath
    } catch (_: Exception) {
      workspace.getProjectDir().absolutePath
    }

    val params =
        JsonObject().apply {
          addProperty("processId", android.os.Process.myPid())
          addProperty("rootUri", workspaceRoot)
          addProperty("rootPath", workspaceRootPath)
          add(
              "workspaceFolders",
              JsonArray().apply {
                add(
                    JsonObject().apply {
                      addProperty("uri", workspaceRoot)
                      addProperty("name", File(workspaceRootPath).name.ifEmpty { "workspace" })
                    },
                )
              },
          )

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
                                        add("markdown")
                                        add("plaintext")
                                      },
                                  )
                                  addProperty("deprecatedSupport", true)
                                  addProperty("preselectSupport", true)

                                  add(
                                      "resolveSupport",
                                      JsonObject().apply {
                                        add(
                                            "properties",
                                            JsonArray().apply {
                                              add("documentation")
                                              add("detail")
                                              add("additionalTextEdits")
                                            },
                                        )
                                      },
                                  )
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
                                  add("markdown")
                                  add("plaintext")
                                },
                            )
                          },
                      )
                      add("definition", JsonObject().apply { addProperty("linkSupport", true) })
                      add("references", JsonObject())
                      add("signatureHelp", JsonObject())
                    },
                )

                add(
                    "workspace",
                    JsonObject().apply {
                      addProperty("applyEdit", true)
                      add(
                          "workspaceEdit",
                          JsonObject().apply { addProperty("documentChanges", true) },
                      )
                      add(
                          "didChangeConfiguration",
                          JsonObject().apply { addProperty("dynamicRegistration", true) },
                      )
                      add("symbol", JsonObject().apply { addProperty("dynamicRegistration", true) })
                    },
                )
              },
          )

          val effectiveClassPaths = classpathProvider.getClasspathList()
          val classpathArray = JsonArray()

          effectiveClassPaths.forEach { path -> classpathArray.add(path) }

          val initOptions =
              JsonObject().apply {
                addProperty("storagePath", workspace.getProjectDir().resolve(".acside").absolutePath)

                addProperty("indexing", "auto")
                addProperty("externalSources", "auto")

                add(
                    "completion",
                    JsonObject().apply {
                      add("snippets", JsonObject().apply { addProperty("enabled", true) })
                    },
                )

                add(
                    "scripts",
                    JsonObject().apply {
                      // Disable Gradle Kotlin script support for Android source editing. The current KLS
                      // build crashes while analyzing settings.gradle.kts, which prevents reliable
                      // diagnostics for normal .kt files.
                      addProperty("enabled", false)
                      addProperty("buildScriptsEnabled", false)
                      // Keep template metadata present for compatibility, but scripts remain disabled.
                      add(
                          "templates",
                          JsonArray().apply {
                            add("kotlin.script.templates.standard.ScriptTemplateWithArgs")
                          },
                      )
                    },
                )

                // Classpath settings
                addProperty("usePredefinedClasspath", true)
                addProperty("disableDependencyResolution", true)
                add("classpath", classpathArray)
              }

          add("initializationOptions", initOptions)

          KslLogs.info("Configured KLS with {} classpath entries", effectiveClassPaths.size)
        }

    KslLogs.info("Full init params created with script support and formatting")
    return params
  }
}