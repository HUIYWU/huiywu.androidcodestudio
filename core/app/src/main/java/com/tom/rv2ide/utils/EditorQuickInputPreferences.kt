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

import com.tom.rv2ide.preferences.internal.EditorPreferences
import org.json.JSONArray

/**
 * Stores the user-configurable expanded quick-input action order.
 *
 * Only known, panel-supported action IDs are persisted. A malformed, empty, or fully invalid
 * value falls back to [defaultExpandedActionIds] so the quick-input panel remains usable.
 */
object EditorQuickInputPreferences {

  data class ExpandedAction(
      val id: String,
      val label: String,
  )

  val supportedExpandedActions =
      listOf(
          ExpandedAction("ide.editor.text.commentline", "Comment line"),
          ExpandedAction("ide.editor.text.duplicateline", "Duplicate line"),
          ExpandedAction("ide.editor.text.deleteline", "Delete line"),
          ExpandedAction("ide.editor.text.indentline", "Indent line"),
          ExpandedAction("ide.editor.text.unindentline", "Unindent line"),
      )

  val defaultExpandedActionIds = supportedExpandedActions.map(ExpandedAction::id)

  private val supportedExpandedActionIds = defaultExpandedActionIds.toSet()

  fun labelForActionId(actionId: String): String {
    return supportedExpandedActions.first { it.id == actionId }.label
  }

  val expandedActionIds: List<String>
    get() = sanitize(readStoredActionIds())

  fun setExpandedActionIds(actionIds: List<String>) {
    val serialized = JSONArray().apply { sanitize(actionIds).forEach { put(it) } }.toString()
    EditorPreferences.quickInputExpandedActionIds = serialized
  }

  fun resetExpandedActionIds() {
    EditorPreferences.quickInputExpandedActionIds = null
  }

  private fun readStoredActionIds(): List<String>? {
    val serialized = EditorPreferences.quickInputExpandedActionIds ?: return null
    return runCatching {
          val array = JSONArray(serialized)
          List(array.length()) { index -> array.optString(index) }
        }
        .getOrNull()
  }

  private fun sanitize(actionIds: List<String>?): List<String> {
    val validIds = actionIds.orEmpty().filter { it in supportedExpandedActionIds }.distinct()
    return validIds.ifEmpty { defaultExpandedActionIds }
  }
}
