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
package com.tom.rv2ide.lsp.xml

import android.os.Handler
import androidx.annotation.RestrictTo
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentSelectedEvent
import com.tom.rv2ide.lsp.api.ICompletionProvider
import com.tom.rv2ide.lsp.api.ILanguageClient
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.IServerSettings
import com.tom.rv2ide.lsp.models.CodeFormatResult
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.ExpandSelectionParams
import com.tom.rv2ide.lsp.models.FormatCodeParams
import com.tom.rv2ide.lsp.models.LSPFailure
import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureHelpParams
import com.tom.rv2ide.lsp.util.NoCompletionsProvider
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticsService
import com.tom.rv2ide.lsp.util.LSPEditorActions
import com.tom.rv2ide.lsp.xml.actions.XmlCodeActionsMenu
import com.tom.rv2ide.lsp.xml.models.XMLServerSettings
import com.tom.rv2ide.lsp.xml.providers.AdvancedEditProvider.onContentChange
import com.tom.rv2ide.lsp.xml.providers.CodeFormatProvider
import com.tom.rv2ide.lsp.xml.providers.XmlCompletionProvider
import com.tom.rv2ide.lsp.xml.providers.XmlDefinitionProvider
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.utils.DocumentUtils
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * Language server implementation for XML files.
 *
 * @author Akash Yadav
 */
class XMLLanguageServer : ILanguageServer {

  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  override var client: ILanguageClient? = null
    private set

  private var settings: IServerSettings? = null

  private val diagnosticsService = XmlDiagnosticsService()
  private val diagnosticHandler = Handler()
  private val analyzeGeneration = AtomicLong(0)
  private val analyzeLaunchInFlight = AtomicBoolean(false)
  private val analyzeRerunRequested = AtomicBoolean(false)
  private var selectedFile: Path? = null
  private val analyzeRunnable = Runnable { analyzeSelected() }

  override val serverId: String = SERVER_ID

  init {
    EventBus.getDefault().register(this)
  }

  override fun shutdown() {
    diagnosticHandler.removeCallbacks(analyzeRunnable)
    selectedFile = null
    if (EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().unregister(this)
    }
  }

  override fun connectClient(client: ILanguageClient?) {
    this.client = client
  }

  override fun applySettings(settings: IServerSettings?) {
    this.settings = settings
  }

  override fun setupWorkspace(workspace: IWorkspace) {
    LSPEditorActions.ensureActionsMenuRegistered(XmlCodeActionsMenu)
  }

  override fun complete(params: CompletionParams?): CompletionResult {
    val completionProvider: ICompletionProvider =
        if (!getSettings().completionsEnabled()) {
          NoCompletionsProvider()
        } else {
          XmlCompletionProvider(getSettings())
        }
    return completionProvider.complete(params)
  }

  fun getSettings(): IServerSettings {
    if (settings == null) {
      settings = XMLServerSettings
    }
    return settings!!
  }

  override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
    return ReferenceResult(emptyList())
  }

  override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
    if (!DocumentUtils.isXmlFile(params.file) || !getSettings().definitionsEnabled()) {
      return DefinitionResult(emptyList())
    }
    return XmlDefinitionProvider().findDefinition(params)
  }

  override suspend fun expandSelection(params: ExpandSelectionParams): Range {
    return params.selection
  }

  override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
    return SignatureHelp(emptyList(), -1, -1)
  }

  override suspend fun analyze(file: Path): DiagnosticResult {
    if (!DocumentUtils.isXmlFile(file)) {
      return DiagnosticResult.NO_UPDATE
    }
    if (!getSettings().diagnosticsEnabled()) {
      return DiagnosticResult(file, emptyList(), XmlDiagnosticsService.CHANNEL)
    }
    return diagnosticsService.analyze(file)
  }

  override fun formatCode(params: FormatCodeParams?): CodeFormatResult {
    return CodeFormatProvider().format(params)
  }

  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onDocumentChange(event: DocumentChangeEvent) {
    if (!DocumentUtils.isXmlFile(event.changedFile)) {
      return
    }
    onContentChange(event)
    selectedFile = event.changedFile
    scheduleDiagnosticAnalysis()
  }

  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onDocumentOpen(event: DocumentOpenEvent) {
    if (!DocumentUtils.isXmlFile(event.openedFile)) {
      return
    }
    selectedFile = event.openedFile
    scheduleDiagnosticAnalysis()
  }

  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onDocumentSelected(event: DocumentSelectedEvent) {
    selectedFile = event.selectedFile
    if (DocumentUtils.isXmlFile(event.selectedFile)) {
      scheduleDiagnosticAnalysis()
    } else {
      // Do not allow an already queued XML result to publish after the user leaves XML editing.
      analyzeGeneration.incrementAndGet()
      diagnosticHandler.removeCallbacks(analyzeRunnable)
    }
  }

  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onDocumentClose(event: DocumentCloseEvent) {
    if (!DocumentUtils.isXmlFile(event.closedFile)) {
      return
    }
    if (selectedFile == event.closedFile) {
      selectedFile = null
      analyzeGeneration.incrementAndGet()
      diagnosticHandler.removeCallbacks(analyzeRunnable)
    }
  }

  private fun scheduleDiagnosticAnalysis() {
    val file = selectedFile
    if (file == null || !DocumentUtils.isXmlFile(file)) {
      return
    }
    analyzeGeneration.incrementAndGet()
    diagnosticHandler.removeCallbacks(analyzeRunnable)
    diagnosticHandler.postDelayed(analyzeRunnable, DIAGNOSTIC_ANALYSIS_DELAY_MS)
  }

  private fun analyzeSelected() {
    val fileToAnalyze = selectedFile ?: return
    if (client == null || !DocumentUtils.isXmlFile(fileToAnalyze)) {
      return
    }
    val requestedGeneration = analyzeGeneration.get()
    if (!analyzeLaunchInFlight.compareAndSet(false, true)) {
      analyzeRerunRequested.set(true)
      return
    }
    CoroutineScope(Dispatchers.Default).launch {
      try {
        if (requestedGeneration != analyzeGeneration.get()) {
          return@launch
        }
        val result = analyze(fileToAnalyze)
        if (requestedGeneration != analyzeGeneration.get() || result == DiagnosticResult.NO_UPDATE) {
          return@launch
        }
        withContext(Dispatchers.Main) {
          if (requestedGeneration == analyzeGeneration.get()) {
            client?.publishDiagnostics(result)
          }
        }
      } finally {
        analyzeLaunchInFlight.set(false)
        if (analyzeRerunRequested.compareAndSet(true, false)) {
          scheduleDiagnosticAnalysis()
        }
      }
    }
  }

  override fun handleFailure(failure: LSPFailure?): Boolean {
    return super<ILanguageServer>.handleFailure(failure)
  }

  companion object {

    const val SERVER_ID = "ide.lsp.xml"
    private const val DIAGNOSTIC_ANALYSIS_DELAY_MS = 400L
  }
}
