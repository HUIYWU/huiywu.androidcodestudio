/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.actions

import android.graphics.drawable.Drawable
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.ActionItem
import com.tom.rv2ide.actions.EditorActionItem
import com.tom.rv2ide.actions.getContext
import com.tom.rv2ide.actions.hasRequiredData
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.lsp.api.ILanguageServerRegistry
import com.tom.rv2ide.lsp.models.CodeActionItem
import com.tom.rv2ide.lsp.models.CodeActionKind
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DocumentChange
import com.tom.rv2ide.lsp.models.PerformCodeActionParams
import com.tom.rv2ide.lsp.models.TextEdit
import com.tom.rv2ide.lsp.xml.XMLLanguageServer
import com.tom.rv2ide.lsp.xml.diagnostics.InvalidAndroidNamespaceDiagnosticData
import com.tom.rv2ide.lsp.xml.diagnostics.MissingAndroidNamespaceDiagnosticData
import com.tom.rv2ide.lsp.xml.isWorkspaceXmlFile
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.resources.R
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File
import org.slf4j.LoggerFactory

/** Adds a missing `xmlns:android` declaration or corrects its URI when diagnostics prove the fix. */
internal class FixAndroidNamespaceAction : EditorActionItem {
  private val log = LoggerFactory.getLogger(FixAndroidNamespaceAction::class.java)

  override val id: String = "ide.editor.lsp.xml.fixAndroidNamespace"
  override var label: String = ""
  override var visible: Boolean = true
  override var enabled: Boolean = true
  override var icon: Drawable? = null
  override var requiresUIThread: Boolean = false
  override var location: ActionItem.Location = ActionItem.Location.EDITOR_CODE_ACTIONS

  private var edit: TextEdit? = null

  override fun prepare(data: ActionData) {
    super.prepare(data)
    edit = null
    label = ""
    visible = false
    enabled = false
    if (!data.hasRequiredData(File::class.java, DiagnosticItem::class.java, CodeEditor::class.java)) {
      markInvisible()
      return
    }
    val file = data[File::class.java]!!
    val diagnostic = data[DiagnosticItem::class.java]!!
    val editor = data[CodeEditor::class.java]!!
    val context = data.getContext()
    val workspaceXml = isWorkspaceXmlFile(file.toPath())
    if (!workspaceXml || context == null) {
      log.warn(
          "XML namespace action trace: hidden reason=invalidContext file={} workspaceXml={} hasContext={} code={} payload={}",
          file,
          workspaceXml,
          context != null,
          diagnostic.code,
          diagnostic.extra?.javaClass?.name,
      )
      markInvisible()
      return
    }

    log.warn(
        "XML namespace action trace: prepare file={} code={} payload={} textLength={}",
        file,
        diagnostic.code,
        diagnostic.extra?.javaClass?.name,
        editor.text.length,
    )
    when (val facts = diagnostic.extra) {
      is MissingAndroidNamespaceDiagnosticData -> {
        if (diagnostic.code != CODE_UNDECLARED_NAMESPACE || facts.prefix != ANDROID_PREFIX) {
          log.warn(
              "XML namespace action trace: hidden reason=unexpectedMissingPayload code={} prefix={}",
              diagnostic.code,
              facts.prefix,
          )
          markInvisible()
          return
        }
        val insertion = namespaceInsertion(editor.text.toString()) ?: run {
          log.warn("XML namespace action trace: hidden reason=noSafeRootInsertion")
          markInvisible()
          return
        }
        edit = insertion
        label = context.getString(R.string.action_add_android_namespace)
        log.warn("XML namespace action trace: visible kind=add range={}", insertion.range)
      }
      is InvalidAndroidNamespaceDiagnosticData -> {
        if (diagnostic.code != CODE_INVALID_ANDROID_NAMESPACE ||
            facts.expectedUri != ANDROID_NAMESPACE_URI ||
            facts.actualUri == facts.expectedUri) {
          markInvisible()
          return
        }
        edit = TextEdit(diagnostic.range, facts.expectedUri)
        label = context.getString(R.string.action_fix_android_namespace)
      }
      else -> {
        log.warn(
            "XML namespace action trace: hidden reason=unsupportedPayload code={} payload={}",
            diagnostic.code,
            facts?.javaClass?.name,
        )
        markInvisible()
        return
      }
    }
    visible = true
    enabled = true
  }

  override suspend fun execAction(data: ActionData): Any {
    val file = data[File::class.java] ?: return false
    val replacement = edit ?: return false
    val server =
        ILanguageServerRegistry.getDefault().getServer(XMLLanguageServer.SERVER_ID)
            as? XMLLanguageServer ?: return false
    val client = server.client ?: return false
    val action =
        CodeActionItem().apply {
          title = label
          kind = CodeActionKind.QuickFix
          changes = listOf(DocumentChange(file.toPath(), listOf(replacement)))
        }
    client.performCodeAction(PerformCodeActionParams(async = false, action = action))
    return true
  }

  internal companion object {
    const val CODE_UNDECLARED_NAMESPACE = "XML003"
    const val CODE_INVALID_ANDROID_NAMESPACE = "XML004"
    const val ANDROID_PREFIX = "android"
    const val ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android"

    internal fun namespaceInsertion(text: String): TextEdit? {
      val tagStart = text.indexOf('<')
      if (tagStart < 0 || text.getOrNull(tagStart + 1) in setOf('/', '!', '?')) return null
      var nameEnd = tagStart + 1
      while (nameEnd < text.length && !text[nameEnd].isWhitespace() && text[nameEnd] != '>' && text[nameEnd] != '/') {
        nameEnd++
      }
      if (nameEnd == tagStart + 1 || nameEnd >= text.length) return null
      val tagEnd = findStartTagEnd(text, nameEnd) ?: return null
      if (text.substring(tagStart, tagEnd).contains("xmlns:android")) return null
      return TextEdit(
          Range(offsetToPosition(text, nameEnd), offsetToPosition(text, nameEnd)),
          "\n    xmlns:android=\"$ANDROID_NAMESPACE_URI\"",
      )
    }

    private fun offsetToPosition(text: String, offset: Int): Position {
      var line = 0
      var lineStart = 0
      for (index in 0 until offset.coerceIn(0, text.length)) {
        if (text[index] == '\n') {
          line++
          lineStart = index + 1
        }
      }
      return Position(line, offset.coerceIn(0, text.length) - lineStart)
    }

    private fun findStartTagEnd(text: String, start: Int): Int? {
      var quote: Char? = null
      for (index in start until text.length) {
        val character = text[index]
        if (quote == null) {
          when (character) {
            '\'', '\"' -> quote = character
            '>' -> return index
            '<' -> return null
          }
        } else if (character == quote) {
          quote = null
        }
      }
      return null
    }
  }
}
