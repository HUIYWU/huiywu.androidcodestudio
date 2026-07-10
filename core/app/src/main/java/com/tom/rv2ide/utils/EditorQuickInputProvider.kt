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

  fun expandedActionItems(): List<ActionQuickItem> {
    return listOf(
        ActionQuickItem("action:comment-line", "Comment", "ide.editor.text.commentline"),
        ActionQuickItem("action:duplicate-line", "Duplicate", "ide.editor.text.duplicateline"),
        ActionQuickItem("action:delete-line", "Delete", "ide.editor.text.deleteline"),
        ActionQuickItem("action:indent-line", "Indent", "ide.editor.text.indentline"),
        ActionQuickItem("action:unindent-line", "Unindent", "ide.editor.text.unindentline"),
    )
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