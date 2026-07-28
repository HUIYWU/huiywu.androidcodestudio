/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.actions

import com.tom.rv2ide.actions.ActionItem
import com.tom.rv2ide.lsp.actions.IActionsMenuProvider

/** XML editor actions contributed by the XML language server. */
internal object XmlCodeActionsMenu : IActionsMenuProvider {
  override val actions: List<ActionItem> = listOf(GoToDefinitionAction())
}
