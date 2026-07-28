/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.actions

import android.graphics.drawable.Drawable
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.ActionItem
import com.tom.rv2ide.actions.EditorActionItem
import com.tom.rv2ide.actions.hasRequiredData
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.editor.api.ILspEditor
import com.tom.rv2ide.lsp.api.ILanguageServerRegistry
import com.tom.rv2ide.lsp.xml.XMLLanguageServer
import com.tom.rv2ide.lsp.xml.isWorkspaceXmlFile
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File

/** Starts XML LSP definition lookup from the editor code-actions menu. */
internal class GoToDefinitionAction : EditorActionItem {
  override val id: String = "ide.editor.lsp.xml.gotoDefinition"
  override var label: String = ""
  override var visible: Boolean = true
  override var enabled: Boolean = true
  override var icon: Drawable? = null
  override var requiresUIThread: Boolean = true
  override var location: ActionItem.Location = ActionItem.Location.EDITOR_CODE_ACTIONS

  override fun prepare(data: ActionData) {
    super.prepare(data)
    if (!data.hasRequiredData(CodeEditor::class.java, File::class.java)) {
      markInvisible()
      return
    }
    label = "Go to Definition"
    val file = data[File::class.java]!!
    visible = isWorkspaceXmlFile(file.toPath())
    enabled = visible && (data[CodeEditor::class.java] is ILspEditor)
  }

  override suspend fun execAction(data: ActionData): Any {
    val editor = data[CodeEditor::class.java] as? ILspEditor ?: return false
    val server = ILanguageServerRegistry.getDefault().getServer(XMLLanguageServer.SERVER_ID)
    if (server == null) return false
    editor.findDefinition()
    return true
  }
}