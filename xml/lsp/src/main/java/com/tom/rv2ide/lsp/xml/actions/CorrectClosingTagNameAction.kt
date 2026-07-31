/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package com.tom.rv2ide.lsp.xml.actions

import android.graphics.drawable.Drawable
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.ActionItem
import com.tom.rv2ide.actions.EditorActionItem
import com.tom.rv2ide.actions.hasRequiredData
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.actions.getContext

import com.tom.rv2ide.lsp.api.ILanguageServerRegistry
import com.tom.rv2ide.lsp.models.CodeActionItem
import com.tom.rv2ide.lsp.models.CodeActionKind
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DocumentChange
import com.tom.rv2ide.lsp.models.PerformCodeActionParams
import com.tom.rv2ide.lsp.models.TextEdit
import com.tom.rv2ide.lsp.xml.XMLLanguageServer
import com.tom.rv2ide.lsp.xml.diagnostics.ClosingTagMismatchDiagnosticData
import com.tom.rv2ide.lsp.xml.isWorkspaceXmlFile
import com.tom.rv2ide.resources.R
import java.io.File

/** Offers a conservative fix for an XML005 closing-tag name mismatch. */
internal class CorrectClosingTagNameAction : EditorActionItem {
  override val id: String = "ide.editor.lsp.xml.correctClosingTagName"
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
    if (!isWorkspaceXmlFile(file.toPath()) || diagnostic.code != CODE_XML_PARSER_SYNTAX) {
      markInvisible()
      return
    }
    val mismatch = diagnostic.extra as? ClosingTagMismatchDiagnosticData ?: run {
      markInvisible()
      return
    }
    val context = data.getContext() ?: run {
      markInvisible()
      return
    }
    replacement = mismatch.expectedName
    label = context.getString(R.string.action_fix_closing_tag)
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
    const val CODE_XML_PARSER_SYNTAX = "XML005"
  }
}
