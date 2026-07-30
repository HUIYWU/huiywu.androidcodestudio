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
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.lsp.api.ILanguageServerRegistry
import com.tom.rv2ide.lsp.models.CodeActionItem
import com.tom.rv2ide.lsp.models.CodeActionKind
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DocumentChange
import com.tom.rv2ide.lsp.models.PerformCodeActionParams
import com.tom.rv2ide.lsp.models.TextEdit
import com.tom.rv2ide.lsp.xml.XMLLanguageServer
import com.tom.rv2ide.lsp.xml.isWorkspaceXmlFile
import com.tom.rv2ide.xml.widgets.WidgetTable
import java.io.File

/** Offers a conservative spelling correction for an AXML001 framework layout tag diagnostic. */
internal class CorrectTagNameAction : EditorActionItem {
  override val id: String = "ide.editor.lsp.xml.correctTagName"
  override var label: String = ""
  override var visible: Boolean = true
  override var enabled: Boolean = true
  override var icon: Drawable? = null
  override var requiresUIThread: Boolean = false
  override var location: ActionItem.Location = ActionItem.Location.EDITOR_CODE_ACTIONS

  private var replacement: String? = null

  override fun prepare(data: ActionData) {
    super.prepare(data)
    replacement = null
    if (!data.hasRequiredData(File::class.java, DiagnosticItem::class.java)) {
      markInvisible()
      return
    }

    val file = data[File::class.java]!!
    val diagnostic = data[DiagnosticItem::class.java]!!
    if (!isWorkspaceXmlFile(file.toPath()) || diagnostic.code != CODE_UNKNOWN_LAYOUT_TAG) {
      markInvisible()
      return
    }

    val tagName = tagNameFromDiagnostic(diagnostic.message) ?: run {
      markInvisible()
      return
    }
    val widgets = Lookup.getDefault().lookup(WidgetTable.COMPLETION_LOOKUP_KEY) ?: run {
      markInvisible()
      return
    }
    // Use the same tag population as layout completion so the correction cannot suggest a name
    // that completion itself would not offer in an XML layout.
    val candidate =
        CorrectAttributeNameAction.findUniqueSuggestion(
            tagName,
            widgets.getAllWidgets().map { it.simpleName }.toSet(),
        ) ?: run {
          markInvisible()
          return
        }

    replacement = candidate
    label = "Change to $candidate"
    visible = true
    enabled = true
  }

  override suspend fun execAction(data: ActionData): Any {
    val diagnostic = data[DiagnosticItem::class.java] ?: return false
    val newName = replacement ?: return false
    val file = data[File::class.java] ?: return false
    val server =
        ILanguageServerRegistry.getDefault().getServer(XMLLanguageServer.SERVER_ID)
            as? XMLLanguageServer ?: return false
    val client = server.client ?: return false

    val action =
        CodeActionItem().apply {
          title = label
          kind = CodeActionKind.QuickFix
          changes = listOf(DocumentChange(file.toPath(), listOf(TextEdit(diagnostic.range, newName))))
        }
    client.performCodeAction(PerformCodeActionParams(async = false, action = action))
    return true
  }

  internal companion object {
    const val CODE_UNKNOWN_LAYOUT_TAG = "AXML001"
    private val TAG_MESSAGE = Regex("^Unknown layout tag '([A-Za-z_][A-Za-z0-9_]*)'$" )

    internal fun tagNameFromDiagnostic(message: String): String? {
      return TAG_MESSAGE.matchEntire(message)?.groupValues?.getOrNull(1)
    }
  }
}