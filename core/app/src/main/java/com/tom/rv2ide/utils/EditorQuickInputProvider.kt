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

package com.tom.rv2ide.utils

import com.tom.rv2ide.models.ActionQuickItem
import com.tom.rv2ide.models.EditorQuickItem
import com.tom.rv2ide.models.Symbol
import com.tom.rv2ide.models.SymbolQuickItem
import java.io.File

object EditorQuickInputProvider {

  fun forFile(file: File?): List<EditorQuickItem> {
    return Symbols.forFile(file).toQuickItems()
  }

  fun plainTextItems(): List<EditorQuickItem> {
    return Symbols.plainTextSymbols.toQuickItems()
  }

  /** Expanded panels append only low-risk, high-frequency editor actions to the current symbols. */
  fun expandedItems(symbolItems: List<EditorQuickItem>): List<EditorQuickItem> {
    return symbolItems + expandedActionItems()
  }

  /**
   * Grid labels stay compact: use at most three ASCII characters or two Asian characters.
   * User configuration supplies only action IDs; labels remain built in so wording changes do not
   * require preference migration.
   */
  fun expandedActionItems(): List<ActionQuickItem> {
    return EditorQuickInputPreferences.expandedActionIds.map(::actionItemForId)
  }

  private fun actionItemForId(actionId: String): ActionQuickItem {
    return when (actionId) {
      "ide.editor.text.commentline" -> ActionQuickItem("action:comment-line", "Com", actionId)
      "ide.editor.text.duplicateline" -> ActionQuickItem("action:duplicate-line", "Dup", actionId)
      "ide.editor.text.deleteline" -> ActionQuickItem("action:delete-line", "Del", actionId)
      "ide.editor.text.indentline" -> ActionQuickItem("action:indent-line", "Ind", actionId)
      "ide.editor.text.unindentline" -> ActionQuickItem("action:unindent-line", "Out", actionId)
      else -> error("Unsupported quick-input action: $actionId")
    }
  }

  fun Symbol.toQuickItem(): SymbolQuickItem {
    return SymbolQuickItem(
        id = "symbol:$commit:$offset:$label",
        label = label,
        commit = commit,
        offset = offset,
    )
  }

  fun List<Symbol>.toQuickItems(): List<EditorQuickItem> {
    return map { it.toQuickItem() }
  }
}