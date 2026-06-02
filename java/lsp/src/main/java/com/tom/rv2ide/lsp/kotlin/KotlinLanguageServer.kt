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
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentSelectedEvent
import com.tom.rv2ide.lsp.api.ILanguageClient
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.IServerSettings
import com.tom.rv2ide.lsp.kotlin.compiler.KotlinCompilerService
import com.tom.rv2ide.lsp.kotlin.etc.LspFeatures
import com.tom.rv2ide.lsp.kotlin.providers.KotlinCodeFormatProvider
import com.tom.rv2ide.lsp.models.*
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.utils.VMUtils
import io.github.rosemoe.sora.widget.CodeEditor
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class KotlinLanguageServer(private val context: Context) : ILanguageServer {

  companion object {
    const val SERVER_ID = "kotlin"
    private val log = LoggerFactory.getLogger(KotlinLanguageServer::class.java)
  }

  private val analyzeTimer = com.tom.rv2ide.lsp.java.utils.AnalyzeTimer { analyzeSelected() }
  private var selectedFile: java.nio.file.Path? = null
  private val diagnosticProvider = KotlinDiagnosticProvider()
  private val backendSpec = KotlinLspBackendFactory.createSpec(context)
  private val processManager: KotlinLspConnection = backendSpec.connection
  private val backendConfigurator: KotlinLspBackendConfigurator = backendSpec.configurator

  private val documentManager = KotlinDocumentManager(processManager) { initialized }
  private val requestHandler = KotlinRequestHandler(processManager, documentManager)
  private val eventHandler = KotlinEventHandler(documentManager)

  private var _client: ILanguageClient? = null
  private var initialized = false
  private var workspaceSetup: KotlinWorkspaceSetup? = null

  private val importAnalyzer = KotlinImportAnalyzer()
  private var compilerService: KotlinCompilerService? = null
  private val quickFixHandler by lazy { KotlinImportQuickFix(documentManager, importAnalyzer) }

  private lateinit var formatProvider: KotlinCodeFormatProvider

  private val diagnosticRenderer = KotlinDiagnosticRenderer()
  private val activeEditors = mutableMapOf<Path, CodeEditor>()
  // Track the last structural fallback we published so we only clear/replace what this local
  // fallback owns, without interfering with server diagnostics for unrelated edits.
  private val localFallbackDiagnostics = ConcurrentHashMap<Path, List<DiagnosticItem>>()

  private val completionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  private val autoImportHandler by lazy { KotlinCompletionAutoImport(documentManager) }

  private lateinit var javaCompilerBridge: KotlinJavaCompilerBridge

  init {
    if (!org.greenrobot.eventbus.EventBus.getDefault().isRegistered(eventHandler)) {
      org.greenrobot.eventbus.EventBus.getDefault().register(eventHandler)
    }

    processManager.setDiagnosticsCallback { diagnostics ->
      KslLogs.debug(
          "KLS diagnostics forwarded from server: file={} count={} summary={}",
          diagnostics.file,
          diagnostics.diagnostics.size,
          summarizeDiagnosticsForTrace(diagnostics.diagnostics),
      )
      if (LspFeatures.isDiagnosticsEnabled() == true) {
        _client?.publishDiagnostics(
            diagnostics.copy(channel = DiagnosticResult.CHANNEL_SERVER)
        )
      }

      // If disabled, just ignore the diagnostics
    }
  }

  override val serverId: String = SERVER_ID
  override val client: ILanguageClient?
    get() = _client

  override fun connectClient(client: ILanguageClient?) {
    this._client = client
    KslLogs.info("Connected language client: {}", client?.javaClass?.simpleName)
  }

  override fun applySettings(settings: IServerSettings?) {
    KslLogs.debug("Applied settings: {}", settings)
  }

  override fun setupWorkspace(workspace: IWorkspace) {
    formatProvider = KotlinCodeFormatProvider(processManager)
    workspaceSetup = KotlinWorkspaceSetup(context, workspace, backendConfigurator, backendSpec.id)
    workspaceSetup?.setup(processManager)


    javaCompilerBridge = KotlinJavaCompilerBridge(workspace)
    requestHandler.setJavaCompilerBridge(javaCompilerBridge)

    // Get compiler service and update import analyzer
    compilerService = findCompilerService(workspace)
    importAnalyzer.updateImportCache(compilerService)

    initialized = true
    documentManager.flushPendingOpens()
    startOrRestartAnalyzeTimer()

    // Subscribe to editor events if not already
    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this)
    }
  }

  fun registerEditor(file: Path, editor: CodeEditor) {
    activeEditors[file] = editor
    KslLogs.info("Registered editor for: {}", file)
  }

  fun unregisterEditor(file: Path) {
    activeEditors.remove(file)
    KslLogs.info("Unregistered editor for: {}", file)
  }

  /** Invalidate cache and trigger reindexing Call this when dependencies change or project syncs */
  fun invalidateCacheAndReindex() {
    KslLogs.info("Invalidating index cache and triggering reindex...")
    workspaceSetup?.getIndexCache()?.clearCache()

  }

  /** Clear all project caches (useful for maintenance) */
  fun clearAllCaches() {
    KslLogs.info("Clearing all KLS caches...")
    workspaceSetup?.getIndexCache()?.clearAllCaches()
  }

  /** Get cache statistics */
  fun getCacheInfo(): String {
    return workspaceSetup?.getIndexCache()?.getCacheStats() ?: "No workspace setup"
  }

  override fun complete(params: CompletionParams?): CompletionResult {
    return if (initialized && params != null) {
      // Use async instead of blocking
      runBlocking {
        withTimeout(3000) {
          val result = async(Dispatchers.Default) { requestHandler.complete(params) }
          result.await()
        }
      }
    } else {
      CompletionResult(emptyList())
    }
  }

  private suspend fun analyzeForMissingImports(file: Path, content: String): DiagnosticResult {
    if (!(file.toString().endsWith(".kt") || file.toString().endsWith(".kts"))) {
      return DiagnosticResult.NO_UPDATE
    }

    return try {
      val importDiagnostics = importAnalyzer.analyzeMissingImports(file, content)
      if (importDiagnostics.isNotEmpty()) {
        DiagnosticResult(file, importDiagnostics)
      } else {
        DiagnosticResult.NO_UPDATE
      }
    } catch (e: Exception) {
      KslLogs.error("Failed to analyze file for imports", e)
      DiagnosticResult.NO_UPDATE
    }
  }

  override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
    return if (initialized) {
      requestHandler.findReferences(params)
    } else {
      ReferenceResult(emptyList())
    }
  }

  override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
    return if (initialized) {
      requestHandler.findDefinition(params)
    } else {
      DefinitionResult(emptyList())
    }
  }

  override suspend fun hover(params: DefinitionParams): MarkupContent {
    return if (initialized && LspFeatures.isHoverEnabled() == true) {
      requestHandler.hover(params)
    } else MarkupContent("", MarkupKind.PLAIN)
  }

  override suspend fun expandSelection(params: ExpandSelectionParams): Range {
    return params.selection
  }

  override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
    return if (initialized) {
      requestHandler.signatureHelp(params)
    } else {
      SignatureHelp(emptyList(), 0, 0)
    }
  }

  override suspend fun analyze(file: Path): DiagnosticResult {
    if (!(file.toString().endsWith(".kt") || file.toString().endsWith(".kts"))) {
      return DiagnosticResult.NO_UPDATE
    }

    return try {
      // IDEEditor.analyze() is used as a local fallback entry (for example after builds). Read from
      // FileManager first so unsaved editor text is analyzed instead of stale disk contents.
      val content = FileManager.getDocumentContents(file)
      val localDiagnostics = diagnosticProvider.analyze(file, content).diagnostics
      val importDiagnostics = importAnalyzer.analyzeMissingImports(file, content)

      val mergedDiagnostics =
          buildList {
            addAll(localDiagnostics)
            addAll(importDiagnostics)
          }

      if (mergedDiagnostics.isNotEmpty()) {
        DiagnosticResult(file, mergedDiagnostics)
      } else {
        DiagnosticResult.NO_UPDATE
      }
    } catch (e: Exception) {
      KslLogs.error("Failed to analyze file", e)
      DiagnosticResult.NO_UPDATE
    }
  }

  private fun findCompilerService(workspace: IWorkspace): KotlinCompilerService? {
    val mainModule =
        workspace
            .getSubProjects()
            .filterIsInstance<com.tom.rv2ide.projects.android.AndroidModule>()
            .firstOrNull { it.isApplication }
            ?: workspace
                .getSubProjects()
                .filterIsInstance<com.tom.rv2ide.projects.android.AndroidModule>()
                .firstOrNull()

    return mainModule?.let { KotlinCompilerProvider.get(it) }
  }

  /**
   * Handles diagnostic click for quick fixes
   *
   * @param file The file containing the diagnostic
   * @param range The range of the diagnostic
   * @return true if quick fix was applied
   */
  fun handleDiagnosticClick(file: Path, range: Range): Boolean {
    return quickFixHandler.applyImportFix(file, range)
  }

  /** Gets available import options for a diagnostic */
  fun getImportOptions(file: Path, range: Range): List<String> {
    return quickFixHandler.getImportOptions(file, range)
  }

  override fun formatCode(params: FormatCodeParams?): CodeFormatResult {
    KslLogs.info(
        "formatCode called - initialized: {}, selectedFile: {}, params: {}",
        initialized,
        selectedFile,
        params != null,
    )

    if (params == null) {
      KslLogs.warn("Format params is null")
      return CodeFormatResult(false, mutableListOf())
    }

    if (!initialized) {
      KslLogs.warn("Server not initialized")
      return CodeFormatResult(false, mutableListOf())
    }

    // Get the file to format - from params if available, otherwise use selectedFile
    val fileToFormat = selectedFile
    if (fileToFormat == null) {
      KslLogs.warn("No file selected for formatting")
      return CodeFormatResult(false, mutableListOf())
    }

    if (!(fileToFormat.toString().endsWith(".kt") || fileToFormat.toString().endsWith(".kts"))) {
      KslLogs.debug("Not a Kotlin file: {}", fileToFormat)
      return CodeFormatResult(false, mutableListOf())
    }

    KslLogs.info("Formatting file: {}", fileToFormat)

    try {
      // Ensure document is opened before formatting
      documentManager.ensureDocumentOpen(fileToFormat)

      // If content is provided in params, sync it first
      if (params.content != null && params.content.toString().isNotEmpty()) {
        val uri = fileToFormat.toUri().toString()
        val currentVersion = documentManager.getDocumentVersion(uri)
        val newVersion = currentVersion + 1
        documentManager.setDocumentVersion(uri, newVersion)
        documentManager.notifyDocumentChange(fileToFormat, params.content.toString(), newVersion)

        // Give server a moment to process the change
        Thread.sleep(100)
      }

      val result = formatProvider.format(fileToFormat, params)

      return result
    } catch (e: Exception) {
      KslLogs.error("Error during format", e)
      return CodeFormatResult(false, mutableListOf())
    }
  }

  override fun handleFailure(failure: LSPFailure?): Boolean {
    KslLogs.error("LSP failure: type={}, error={}", failure?.type, failure?.error?.message)
    return false
  }

  override fun shutdown() {
    KslLogs.info("Shutting down Kotlin Language Server...")
    completionScope.cancel()
    try {
      org.greenrobot.eventbus.EventBus.getDefault().unregister(eventHandler)
      if (EventBus.getDefault().isRegistered(this)) {
        EventBus.getDefault().unregister(this)
      }
    } catch (e: Exception) {
      KslLogs.warn("Error unregistering from EventBus", e)
    }
    processManager.shutdown()
    importAnalyzer.clearCache()
    initialized = false
    analyzeTimer.cancel()
    KslLogs.info("Kotlin Language Server shutdown complete")
  }

  private fun startOrRestartAnalyzeTimer() {
    if (VMUtils.isJvm()) return
    if (!analyzeTimer.isStarted) analyzeTimer.start() else analyzeTimer.restart()
  }
  private fun summarizeDiagnosticsForTrace(diagnostics: List<DiagnosticItem>, limit: Int = 3): String {
    if (diagnostics.isEmpty()) return "[]"
    return diagnostics
        .take(limit)
        .joinToString(prefix = "[", postfix = if (diagnostics.size > limit) ", ...]" else "]") { diagnostic ->
          val code = diagnostic.code.ifBlank { "<no-code>" }
          val source = diagnostic.source.ifBlank { "<no-source>" }
          val message = diagnostic.message.replace("\n", " ").take(80)
          "$code|$source|$message"
        }
  }

  private fun publishDiagnosticsToEditor(result: DiagnosticResult) {
    if (result == DiagnosticResult.NO_UPDATE) return


    try {
      // Get the file content to convert positions
      val content = result.file.toFile().readText()

      // Convert to diagnostic regions
      val diagnosticRegions =
          result.diagnostics.map { diagnostic -> diagnostic.asDiagnosticRegion(content) }

      // Publish to client
      _client?.publishDiagnostics(result)

      KslLogs.info("Published {} diagnostics for: {}", diagnosticRegions.size, result.file)
    } catch (e: Exception) {
      KslLogs.error("Failed to publish diagnostics", e)
    }
  }

  private fun publishLocalStructuralFallback(file: Path) {
    try {
      val content = FileManager.getDocumentContents(file)
      val structuralDiagnostics =
          diagnosticProvider
              .analyze(file, content)
              .diagnostics
              .filter { it.code.startsWith("STRUCTURAL_") }

      val previousStructuralDiagnostics = localFallbackDiagnostics[file].orEmpty()
      val hasStructuralChanges = structuralDiagnostics != previousStructuralDiagnostics
      if (!hasStructuralChanges) {
        return
      }

      localFallbackDiagnostics[file] = structuralDiagnostics

      if (structuralDiagnostics.isNotEmpty()) {
        KslLogs.debug(
            "KLS TRACE diagnostics.forward source=local_structural file={} count={} summary={}",
            file,
            structuralDiagnostics.size,
            summarizeDiagnosticsForTrace(structuralDiagnostics),
        )
        // This local fallback only covers obvious delimiter damage during live editing. Keep it
        // isolated from server diagnostics instead of trying to merge uncertain snapshots.
        publishDiagnosticsToEditor(
            DiagnosticResult(
                file,
                structuralDiagnostics,
                DiagnosticResult.CHANNEL_LOCAL_STRUCTURAL,
            )
        )
      } else if (previousStructuralDiagnostics.isNotEmpty()) {
        KslLogs.debug("KLS TRACE diagnostics.clear source=local_structural file={}", file)
        _client?.publishDiagnostics(
            DiagnosticResult(
                file,
                emptyList(),
                DiagnosticResult.CHANNEL_LOCAL_STRUCTURAL,
            )
        )
      }

    } catch (e: Exception) {
      KslLogs.warn("Failed to publish local structural fallback for {}", file, e)
    }
  }

  private fun analyzeSelected() {
    val file = selectedFile ?: return
    CoroutineScope(Dispatchers.Default).launch {
      // Keep the document opened in KLS, but avoid forcing an extra didSave here.
      // DocumentChangeEvent already sends didChange + debounced didSave with the latest in-memory text.
      documentManager.ensureDocumentOpen(file)
      publishLocalStructuralFallback(file)
    }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onContentChange(event: DocumentChangeEvent) {
    if (
        !(event.changedFile.toString().endsWith(".kt") ||
            event.changedFile.toString().endsWith(".kts"))
    )
        return
    startOrRestartAnalyzeTimer()
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onFileOpened(event: DocumentOpenEvent) {
    if (
        !(event.openedFile.toString().endsWith(".kt") ||
            event.openedFile.toString().endsWith(".kts"))
    )
        return
    selectedFile = event.openedFile
    startOrRestartAnalyzeTimer()
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  fun onFileSelected(event: DocumentSelectedEvent) {
    KslLogs.info("=== FILE SELECTED EVENT: {}", event.selectedFile)
    selectedFile = event.selectedFile
    if (
        event.selectedFile.toString().endsWith(".kt") ||
            event.selectedFile.toString().endsWith(".kts")
    ) {
      documentManager.ensureDocumentOpen(event.selectedFile)
    }
  }
}
