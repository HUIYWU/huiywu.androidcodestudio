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
package com.tom.rv2ide.lsp.xml.actions

import android.graphics.drawable.Drawable
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.ActionItem
import com.tom.rv2ide.actions.EditorActionItem
import com.tom.rv2ide.actions.hasRequiredData
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.actions.getContext
import com.tom.rv2ide.editor.api.ILspEditor
import com.tom.rv2ide.lsp.api.ILanguageServerRegistry
import com.tom.rv2ide.lsp.xml.XMLLanguageServer
import com.tom.rv2ide.lsp.xml.isWorkspaceXmlFile
import com.tom.rv2ide.resources.R
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
    val context = data.getContext() ?: run {
      markInvisible()
      return
    }
    label = context.getString(R.string.action_goto_definition)
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