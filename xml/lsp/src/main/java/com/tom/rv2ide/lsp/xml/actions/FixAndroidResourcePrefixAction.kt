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
import com.tom.rv2ide.lsp.api.ILanguageServerRegistry
import com.tom.rv2ide.lsp.models.CodeActionItem
import com.tom.rv2ide.lsp.models.CodeActionKind
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DocumentChange
import com.tom.rv2ide.lsp.models.PerformCodeActionParams
import com.tom.rv2ide.lsp.models.TextEdit
import com.tom.rv2ide.lsp.xml.XMLLanguageServer
import com.tom.rv2ide.lsp.xml.diagnostics.MissingFrameworkResourcePrefixDiagnosticData
import com.tom.rv2ide.lsp.xml.isWorkspaceXmlFile
import com.tom.rv2ide.resources.R
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File

/** Adds `android:` only when the AXML003 diagnostic has resolved an exact framework replacement. */
internal class FixAndroidResourcePrefixAction : EditorActionItem {
  override val id = "ide.editor.lsp.xml.fixAndroidResourcePrefix"
  override var label = ""
  override var visible = true
  override var enabled = true
  override var icon: Drawable? = null
  override var requiresUIThread = false
  override var location = ActionItem.Location.EDITOR_CODE_ACTIONS
  private var edit: TextEdit? = null

  override fun prepare(data: ActionData) {
    super.prepare(data)
    edit = null
    label = ""
    visible = false
    enabled = false
    if (!data.hasRequiredData(File::class.java, DiagnosticItem::class.java, CodeEditor::class.java)) return
    val file = data[File::class.java]!!
    val diagnostic = data[DiagnosticItem::class.java]!!
    val context = data.getContext()
    val replacement = replacementFor(diagnostic)
    if (!isWorkspaceXmlFile(file.toPath()) || context == null || replacement == null) return
    edit = TextEdit(diagnostic.range, replacement)
    label = context.getString(R.string.action_add_android_resource_prefix)
    visible = true
    enabled = true
  }

  override suspend fun execAction(data: ActionData): Any {
    val file = data[File::class.java] ?: return false
    val edit = edit ?: return false
    val server = ILanguageServerRegistry.getDefault().getServer(XMLLanguageServer.SERVER_ID) as? XMLLanguageServer ?: return false
    val client = server.client ?: return false
    client.performCodeAction(PerformCodeActionParams(async = false, action = CodeActionItem().apply {
      title = label
      kind = CodeActionKind.QuickFix
      changes = listOf(DocumentChange(file.toPath(), listOf(edit)))
    }))
    return true
  }

  internal companion object {
    const val CODE_UNRESOLVED_RESOURCE = "AXML003"
    internal fun replacementFor(diagnostic: DiagnosticItem): String? {
      val facts = diagnostic.extra as? MissingFrameworkResourcePrefixDiagnosticData ?: return null
      return facts.replacement.takeIf {
        diagnostic.code == CODE_UNRESOLVED_RESOURCE && facts.originalReference != it
      }
    }
  }
}