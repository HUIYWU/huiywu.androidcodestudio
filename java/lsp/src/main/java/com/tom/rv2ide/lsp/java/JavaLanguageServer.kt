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
package com.tom.rv2ide.lsp.java

import com.tom.rv2ide.common.logging.IdeLogConfig
import androidx.annotation.RestrictTo
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentSelectedEvent
import com.tom.rv2ide.eventbus.events.file.FileCreationEvent
import com.tom.rv2ide.eventbus.events.file.FileDeletionEvent
import com.tom.rv2ide.eventbus.events.file.FileRenameEvent
import com.tom.rv2ide.javac.services.fs.CacheFSInfoSingleton
import com.tom.rv2ide.javac.services.fs.CachingJarFileSystemProvider.clearCache
import com.tom.rv2ide.javac.services.fs.CachingJarFileSystemProvider.clearCachesForPaths
import com.tom.rv2ide.lsp.api.ILanguageClient
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.IServerSettings
import com.tom.rv2ide.lsp.internal.model.CachedCompletion
import com.tom.rv2ide.lsp.java.actions.JavaCodeActionsMenu
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService
import com.tom.rv2ide.lsp.java.compiler.SourceFileManager
import com.tom.rv2ide.lsp.java.kotlin.KotlinClassOutputProvider
import com.tom.rv2ide.lsp.java.kotlin.KotlinJvmTypeIndex
import com.tom.rv2ide.lsp.java.models.JavaServerSettings
import com.tom.rv2ide.lsp.java.providers.CodeFormatProvider
import com.tom.rv2ide.lsp.java.providers.CompletionProvider
import com.tom.rv2ide.lsp.java.providers.DefinitionProvider
import com.tom.rv2ide.lsp.java.providers.JavaDiagnosticProvider
import com.tom.rv2ide.lsp.java.providers.JavaSelectionProvider
import com.tom.rv2ide.lsp.java.providers.ReferenceProvider
import com.tom.rv2ide.lsp.java.providers.SignatureProvider
import com.tom.rv2ide.lsp.java.providers.snippet.JavaSnippetRepository.init
import com.tom.rv2ide.lsp.java.utils.AnalyzeTimer
import com.tom.rv2ide.preferences.internal.JavaPreferences
import com.tom.rv2ide.lsp.java.utils.CancelChecker.Companion.isCancelled
import com.tom.rv2ide.lsp.models.CodeFormatResult
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.ExpandSelectionParams
import com.tom.rv2ide.lsp.models.FailureType
import com.tom.rv2ide.lsp.models.FormatCodeParams
import com.tom.rv2ide.lsp.models.LSPFailure
import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureHelpParams
import com.tom.rv2ide.lsp.util.LSPEditorActions
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.FileManager.getActiveDocumentCount
import com.tom.rv2ide.projects.IProjectManager.Companion.getInstance
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.events.LazyModuleActivatedEvent
import com.tom.rv2ide.projects.events.LazyModuleEvictedEvent
import com.tom.rv2ide.utils.DocumentUtils
import com.tom.rv2ide.utils.VMUtils
import java.nio.file.Path
import java.util.Objects
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory

class JavaLanguageServer : ILanguageServer {

  private val completionProvider: CompletionProvider = CompletionProvider()
  private val diagnosticProvider: JavaDiagnosticProvider?
  private var lastHeavyModuleCleanupAt: Long = 0L
  override var client: ILanguageClient? = null
    private set

  private var _settings: IServerSettings? = null
  private var selectedFile: Path? = null
  private val timer = AnalyzeTimer { analyzeSelected() }
  private val analyzeGeneration = AtomicLong(0)
  private val analyzeLaunchInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
  private val analyzeRerunRequested = java.util.concurrent.atomic.AtomicBoolean(false)
  private val lastInteractiveRequestAt = AtomicLong(0)
  private var cachedCompletion: CachedCompletion
  private var lastJavaChangeDelta = 0

  val settings: IServerSettings
    get() {
      return _settings ?: JavaServerSettings.getInstance().also { _settings = it }
    }

  override val serverId: String = SERVER_ID

  companion object {

    const val SERVER_ID = "ide.lsp.java"
    private const val LARGE_CHANGE_DELTA_FOR_DIAGNOSTIC_DEBOUNCE = 2_000
    private const val LARGE_CHANGE_ANALYZE_INTERVAL_MS = 1_500L
    private const val SIGNATURE_HELP_DIAGNOSTIC_GRACE_MS = 1_200L
    private const val HEAVY_COMPOSITE_IDLE_EVICTION_MS = 5 * 60_000L
    private val log = LoggerFactory.getLogger(JavaLanguageServer::class.java)
  }

  init {
    diagnosticProvider = JavaDiagnosticProvider()
    cachedCompletion = CachedCompletion.EMPTY

    applySettings(JavaServerSettings.getInstance())

    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this)
    }

    init()
  }

  override fun shutdown() {
    JavaCompilerProvider.getInstance().destroy()
    SourceFileManager.clearCache()
    KotlinJvmTypeIndex.clear()
    KotlinClassOutputProvider.clearCache()
    CacheFSInfoSingleton.clearCache()
    clearCache()
    EventBus.getDefault().unregister(this)
    timer.cancel()
  }

  override fun connectClient(client: ILanguageClient?) {
    this.client = client
  }

  override fun applySettings(settings: IServerSettings?) {
    this._settings = settings
  }

  override fun setupWorkspace(workspace: IWorkspace) {
    LSPEditorActions.ensureActionsMenuRegistered(JavaCodeActionsMenu)

    // Once we have workspace initialized
    // Destory the NO_MODULE_COMPILER instance
    JavaCompilerService.NO_MODULE_COMPILER.destroy()

    // Clear cached file managers and Kotlin source/class-output symbol snapshots.
    SourceFileManager.clearCache()
    KotlinJvmTypeIndex.clear()
    KotlinClassOutputProvider.clearCache()

    // Clear cached JAR file system for R.jar
    // Using the cached instance will result in completions not being updated for updated resources
    // TODO Clearing caches for JAR files ending with '/R.jar' is probably not a good idea
    //    Maybe this could be improved by using data from the AndroidModule workspace model
    clearCachesForPaths { path: String -> path.endsWith("/R.jar") }

    // Clear cached module-specific compilers
    JavaCompilerProvider.getInstance().destroy()

    // Cache classpath locations for eagerly active modules only.
    for (subModule in workspace.getSubProjects()) {
      if (subModule !is ModuleProject || subModule.path == workspace.getRootProject().path) {
        continue
      }
      if (subModule.isLazyCompositeBuildModule()) {
        log.info("Skipping SourceFileManager warm-up for lazy composite module: {}", subModule.path)
        continue
      }
      SourceFileManager.forModule(subModule)
    }
    startOrRestartAnalyzeTimer()
  }
  override fun complete(params: CompletionParams?): CompletionResult {
    lastInteractiveRequestAt.set(System.currentTimeMillis())
    val compiler = getCompiler(params!!.file)
    if (!settings.completionsEnabled() || !completionProvider.canComplete(params.file)) {
      return CompletionResult.EMPTY
    }


    if (diagnosticProvider!!.isAnalyzing()) {
      diagnosticProvider.cancel()
    }

    completionProvider.reset(compiler, settings, cachedCompletion) {
        cachedCompletion: CachedCompletion ->
      updateCachedCompletion(cachedCompletion)
    }

    val result = completionProvider.complete(params)

    // log.warn(result.toString())

    return result
  }

  override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
    val compiler = getCompiler(params.file)
    return if (!settings.referencesEnabled()) {
      ReferenceResult(emptyList())
    } else ReferenceProvider(compiler, params.cancelChecker).findReferences(params)
  }

  override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
    val compiler = getCompiler(params.file)
    return if (!settings.definitionsEnabled()) {
      DefinitionResult(emptyList())
    } else DefinitionProvider(compiler, settings, params.cancelChecker).findDefinition(params)
  }

  override suspend fun expandSelection(params: ExpandSelectionParams): Range {
    val compiler = getCompiler(params.file)
    return if (!settings.smartSelectionsEnabled()) {
      params.selection
    } else JavaSelectionProvider(compiler).expandSelection(params)
  }

  override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
    lastInteractiveRequestAt.set(System.currentTimeMillis())
    val compiler = getCompiler(params.file)
    return if (!settings.signatureHelpEnabled()) {
      SignatureHelp(emptyList(), -1, -1)
    } else SignatureProvider(compiler, params.cancelChecker).signatureHelp(params)
  }

  override suspend fun hover(params: DefinitionParams): com.tom.rv2ide.lsp.models.MarkupContent {
    // Java LSP does not currently implement hover; return empty
    return com.tom.rv2ide.lsp.models.MarkupContent()
  }

  override suspend fun analyze(file: Path): DiagnosticResult {
    if (!settings.diagnosticsEnabled() || !DocumentUtils.isJavaFile(file)) {
      return DiagnosticResult.NO_UPDATE
    }

    if (!settings.codeAnalysisEnabled()) {
      return DiagnosticResult.NO_UPDATE
    }

    val workspace = getInstance().getWorkspace() ?: return DiagnosticResult.NO_UPDATE
    val module = workspace.findModuleForFile(file, false) ?: return DiagnosticResult.NO_UPDATE
    if (module.isHeavyCompositeBuildModule() && !module.hasBeenIndexed()) {
      workspace.ensureModuleActivated(module)
      log.info("Deferring analysis until heavy composite module activation completes: module={} file={}", module.path, file)
      return DiagnosticResult.NO_UPDATE
    }

    return diagnosticProvider!!.analyze(file)
  }

  override fun formatCode(params: FormatCodeParams?): CodeFormatResult {
    return CodeFormatProvider(settings).format(params)
  }

  override fun handleFailure(failure: LSPFailure?): Boolean {
    return when (failure!!.type) {
      FailureType.COMPLETION -> {
        if (isCancelled(failure.error)) {
          return true
        }
        JavaCompilerProvider.getInstance().destroy()
        true
      }
    }
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  fun getCompiler(file: Path?): JavaCompilerService {
    if (!DocumentUtils.isJavaFile(file)) {
      return JavaCompilerService.NO_MODULE_COMPILER
    }
    val workspace = getInstance().getWorkspace() ?: return JavaCompilerService.NO_MODULE_COMPILER
    val module =
      workspace.findModuleForFile(file!!) ?: return JavaCompilerService.NO_MODULE_COMPILER
    workspace.ensureModuleActivated(module)
    return JavaCompilerProvider.get(module)
  }

  private fun updateCachedCompletion(cachedCompletion: CachedCompletion) {
    Objects.requireNonNull(cachedCompletion)
    this.cachedCompletion = cachedCompletion
  }

  private fun maybeEvictIdleHeavyCompositeModules() {
    val now = System.currentTimeMillis()
    if (now - lastHeavyModuleCleanupAt < 60_000L) {
      return
    }
    lastHeavyModuleCleanupAt = now
    val workspace = getInstance().getWorkspace() ?: return
    workspace.getSubProjects()
      .filterIsInstance<ModuleProject>()
      .filter { it.isHeavyCompositeBuildModule() }
      .forEach { module ->
        val evicted = module.evictIfIdle(HEAVY_COMPOSITE_IDLE_EVICTION_MS)
        if (evicted) {
          log.info("Idle heavy composite module eviction observed by JavaLanguageServer: {}", module.path)
        }
      }
  }

  private fun shouldForceAnalyzeTimerRestart(): Boolean {
    val sinceInteractive = System.currentTimeMillis() - lastInteractiveRequestAt.get()
    return sinceInteractive < 0 || sinceInteractive >= SIGNATURE_HELP_DIAGNOSTIC_GRACE_MS
  }

  private fun startOrRestartAnalyzeTimer(forceRestart: Boolean = true) {
    if (VMUtils.isJvm()) {
      return
    }
    val nextGeneration = analyzeGeneration.incrementAndGet()
    val baseInterval =
        if (abs(lastJavaChangeDelta) >= LARGE_CHANGE_DELTA_FOR_DIAGNOSTIC_DEBOUNCE) {
          LARGE_CHANGE_ANALYZE_INTERVAL_MS
        } else {
          AnalyzeTimer.DEFAULT_INTERVAL
        }
    val now = System.currentTimeMillis()
    val sinceInteractive = now - lastInteractiveRequestAt.get()
    val interactiveDelay =
        when {
          sinceInteractive < 0 -> 0L
          sinceInteractive < SIGNATURE_HELP_DIAGNOSTIC_GRACE_MS ->
              SIGNATURE_HELP_DIAGNOSTIC_GRACE_MS - sinceInteractive
          else -> 0L
        }
    val interval = maxOf(baseInterval.toLong(), interactiveDelay)
    val shouldRestart = !timer.isStarted || forceRestart || timer.interval < interval
    if (timer.interval != interval) {
      timer.interval = interval
    }
    if (!timer.isStarted) {
      timer.start()
    } else if (shouldRestart) {
      timer.restart()
    }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onContentChange(event: DocumentChangeEvent) {
    if (DocumentUtils.isKotlinFile(event.changedFile)) {
      log.warn(
        "Kotlin ABI TRACE contentChanged file={} version={} delta={} selectedFile={}",
        event.changedFile,
        event.version,
        event.changeDelta,
        selectedFile,
      )
      invalidateKotlinAbi(event.changedFile, clearFileManagers = false)
      return
    }
    if (!DocumentUtils.isJavaFile(event.changedFile)) {
      return
    }
    lastJavaChangeDelta = event.changeDelta

    // TODO Find an alternative to efficiently update changeDelta in JavaCompilerService instance
    JavaCompilerService.NO_MODULE_COMPILER.onDocumentChange(event)
    val workspace = getInstance().getWorkspace()
    val module = workspace?.findModuleForFile(event.changedFile, true)
    if (module != null) {
      workspace.ensureModuleActivated(module)
      val compiler = JavaCompilerProvider.get(module)
      compiler.onDocumentChange(event)
    }
    startOrRestartAnalyzeTimer(forceRestart = shouldForceAnalyzeTimerRestart())
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onFileSelected(event: DocumentSelectedEvent) {
    selectedFile = event.selectedFile
    // Kotlin source changes can occur while its editor is selected. Once the user returns to an
    // open Java file, force a full diagnostics pass so stale Kotlin ABI/classpath state is never
    // retained merely because no Java document change was made.
    if (DocumentUtils.isJavaFile(event.selectedFile)) {
      lastJavaChangeDelta = 0
      startOrRestartAnalyzeTimer(forceRestart = true)
    }
  }
  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onFileOpened(event: DocumentOpenEvent) {
    selectedFile = event.openedFile
    lastJavaChangeDelta = 0

    // Some editor flows intentionally omit the eager text payload for very large files to avoid an
    // additional full-buffer String allocation during open. Recover the initial snapshot from the
    // active document cache when that happens so Java analysis still sees the correct content.
    if (event.text.isBlank()) {
      event.text = com.tom.rv2ide.projects.FileManager.getDocumentContents(event.openedFile)
    }

    startOrRestartAnalyzeTimer(forceRestart = shouldForceAnalyzeTimerRestart())
  }


  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onFileClosed(event: DocumentCloseEvent) {
    diagnosticProvider?.clearTimestamp(event.closedFile)

    if (getActiveDocumentCount() == 0) {
      selectedFile = null
      timer.cancel()
    }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onLazyModuleActivated(event: LazyModuleActivatedEvent) {
    val fileToAnalyze = selectedFile ?: return
    if (!DocumentUtils.isJavaFile(fileToAnalyze)) {
      return
    }
    if (!event.module.isFromThisModule(fileToAnalyze)) {
      return
    }
    log.info("Re-analyzing selected file after lazy module activation: module={} file={}", event.module.path, fileToAnalyze)
    analyzeSelected()
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onKotlinFileCreated(event: FileCreationEvent) {
    log.warn("Kotlin ABI TRACE fileCreated file={} isKotlin={}", event.file, DocumentUtils.isKotlinFile(event.file.toPath()))
    if (DocumentUtils.isKotlinFile(event.file.toPath())) {
      invalidateKotlinAbi(event.file.toPath(), clearFileManagers = false)
    }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onKotlinFileDeleted(event: FileDeletionEvent) {
    log.warn("Kotlin ABI TRACE fileDeleted file={} extension={}", event.file, event.file.extension)
    if (event.file.extension == "kt" || event.file.extension == "kts") {
      invalidateKotlinAbi(event.file.toPath(), clearFileManagers = false)
    }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onKotlinFileRenamed(event: FileRenameEvent) {
    if (event.file.extension == "kt" || event.file.extension == "kts"
        || DocumentUtils.isKotlinFile(event.newFile.toPath())) {
      invalidateKotlinAbi(event.file.toPath(), clearFileManagers = false)
      invalidateKotlinAbi(event.newFile.toPath(), clearFileManagers = false)
    }
  }

  private fun invalidateKotlinAbi(kotlinFile: Path, clearFileManagers: Boolean) {
    log.warn(
      "Kotlin ABI TRACE invalidateStart file={} enabled={} selectedFile={} clearFileManagers={}",
      kotlinFile,
      JavaPreferences.isJavaKotlinRecognitionEnabled,
      selectedFile,
      clearFileManagers,
    )
    if (!JavaPreferences.isJavaKotlinRecognitionEnabled) {
      return
    }
    val workspace = getInstance().getWorkspace()
    val module = workspace?.findModuleForFile(kotlinFile.toFile(), false)
        ?: workspace?.findModuleForFile(kotlinFile.toFile(), true)
    log.warn("Kotlin ABI TRACE invalidateResolved file={} module={}", kotlinFile, module?.path)
    if (module != null) {
      KotlinJvmTypeIndex.invalidate(module)
      JavaCompilerProvider.getInstance().destroy(module)
    } else {
      // A deletion is commonly delivered after its file can no longer be resolved to a module.
      // Prefer a conservative global reset over retaining an ABI stub for a deleted Kotlin type.
      KotlinJvmTypeIndex.clear()
      JavaCompilerProvider.getInstance().destroy()
    }
    KotlinClassOutputProvider.clearCache()
    cachedCompletion = CachedCompletion.EMPTY
    if (clearFileManagers) {
      SourceFileManager.clearCache()
    }
    val currentJavaFile = selectedFile
    val schedulesJavaDiagnostics = currentJavaFile != null
        && DocumentUtils.isJavaFile(currentJavaFile)
        && (module == null || module.isFromThisModule(currentJavaFile))
    log.warn(
      "Kotlin ABI TRACE invalidateFinish file={} module={} selectedJavaFile={} schedulesDiagnostics={}",
      kotlinFile,
      module?.path,
      currentJavaFile,
      schedulesJavaDiagnostics,
    )
    if (schedulesJavaDiagnostics) {
      lastJavaChangeDelta = 0
      startOrRestartAnalyzeTimer(forceRestart = true)
    }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onLazyModuleEvicted(event: LazyModuleEvictedEvent) {
    JavaCompilerProvider.getInstance().destroy(event.module)
  }

  private fun analyzeSelected() {
    val fileToAnalyze = selectedFile
    if (fileToAnalyze == null || client == null) {
      return
    }
    val now = System.currentTimeMillis()
    val sinceInteractive = now - lastInteractiveRequestAt.get()
    if (sinceInteractive in 0 until SIGNATURE_HELP_DIAGNOSTIC_GRACE_MS) {
      startOrRestartAnalyzeTimer(forceRestart = false)
      return
    }
    val requestedGeneration = analyzeGeneration.get()
    if (!analyzeLaunchInFlight.compareAndSet(false, true)) {
      analyzeRerunRequested.set(true)
      if (IdeLogConfig.shouldLogInfo()) {
        log.info(
          "Analyze coalesced while in flight requestedGeneration={} currentGeneration={} file={}",
          requestedGeneration,
          analyzeGeneration.get(),
          fileToAnalyze,
        )
      }
      return
    }
    CoroutineScope(Dispatchers.Default).launch {
      try {
        if (requestedGeneration != analyzeGeneration.get()) {
          if (IdeLogConfig.shouldLogInfo()) {
            log.info(
              "Analyze skipped before start due to newer request requestedGeneration={} currentGeneration={} file={}",
              requestedGeneration,
              analyzeGeneration.get(),
              fileToAnalyze,
            )
          }
          return@launch
        }
        maybeEvictIdleHeavyCompositeModules()
        if (requestedGeneration != analyzeGeneration.get()) {
          if (IdeLogConfig.shouldLogInfo()) {
            log.info(
              "Analyze skipped after pre-work due to newer request requestedGeneration={} currentGeneration={} file={}",
              requestedGeneration,
              analyzeGeneration.get(),
              fileToAnalyze,
            )
          }
          return@launch
        }
        val result = analyze(fileToAnalyze)
        if (requestedGeneration != analyzeGeneration.get()) {
          return@launch
        }
        if (result == DiagnosticResult.NO_UPDATE) {
          return@launch
        }
        withContext(Dispatchers.Main) {
          if (requestedGeneration == analyzeGeneration.get()) {
            client?.publishDiagnostics(result)
          } else if (IdeLogConfig.shouldLogInfo()) {
            log.info(
              "Analyze publish skipped due to newer request requestedGeneration={} currentGeneration={} file={}",
              requestedGeneration,
              analyzeGeneration.get(),
              fileToAnalyze,
            )
          }
        }
      } finally {
        analyzeLaunchInFlight.set(false)
        if (analyzeRerunRequested.compareAndSet(true, false)) {
          if (IdeLogConfig.shouldLogInfo()) {
            log.info(
              "Analyze trailing rerun requested currentGeneration={} selectedFile={}",
              analyzeGeneration.get(),
              selectedFile,
            )
          }
          analyzeSelected()
        }
      }
    }
  }
}
