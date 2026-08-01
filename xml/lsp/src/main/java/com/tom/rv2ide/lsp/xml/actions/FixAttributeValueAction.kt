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
import com.tom.rv2ide.lsp.xml.diagnostics.InvalidAttributeValueDiagnosticData
import com.tom.rv2ide.lsp.xml.isWorkspaceXmlFile
import com.tom.rv2ide.resources.R
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File

/** Applies a diagnostic-provided, lossless normalization for an AXML004 attribute value. */
internal class FixAttributeValueAction : EditorActionItem {

  override val id: String = "ide.editor.lsp.xml.fixAttributeValue"
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
    val context = data.getContext()
    val replacement = replacementFor(diagnostic)
    if (!isWorkspaceXmlFile(file.toPath()) || context == null || replacement == null) {
      markInvisible()
      return
    }
    edit = TextEdit(diagnostic.range, replacement)
    label = context.getString(R.string.action_fix_attribute_value)
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
    const val CODE_INVALID_ATTRIBUTE_VALUE = "AXML004"

    internal fun replacementFor(diagnostic: DiagnosticItem): String? {
      val facts = diagnostic.extra as? InvalidAttributeValueDiagnosticData ?: return null
      if (diagnostic.code != CODE_INVALID_ATTRIBUTE_VALUE || facts.actualValue == facts.replacement) {
        return null
      }
      return facts.replacement
    }
  }
}
