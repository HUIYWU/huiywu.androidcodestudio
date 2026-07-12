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

import android.content.Context
import androidx.annotation.StringRes
import com.tom.rv2ide.models.ActionQuickItem
import com.tom.rv2ide.models.EditorQuickItem
import com.tom.rv2ide.models.SymbolQuickItem
import com.tom.rv2ide.preferences.QuickInputTextType
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.preferences.internal.EditorPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Persisted, per-text-type expanded quick-input items. */
object EditorQuickInputPreferences {
  const val MAX_ITEMS = 30

  data class ExpandedAction(val id: String, @StringRes val labelRes: Int)
  val supportedExpandedActions = listOf(
      ExpandedAction("ide.editor.text.uppercase", R.string.action_convert_to_uppercase),
      ExpandedAction("ide.editor.text.lowercase", R.string.action_convert_to_lowercase),
      ExpandedAction("ide.editor.text.deleteline", R.string.action_delete_line),
      ExpandedAction("ide.editor.text.duplicateline", R.string.action_duplicate_line),
      ExpandedAction("ide.editor.text.copyline", R.string.action_copy_line),
      ExpandedAction("ide.editor.text.commentline", R.string.action_comment_line),
      ExpandedAction("ide.editor.text.movelineup", R.string.action_move_line_up),
      ExpandedAction("ide.editor.text.movelinedown", R.string.action_move_line_down),
      ExpandedAction("ide.editor.text.insertlineabove", R.string.action_insert_line_above),
      ExpandedAction("ide.editor.text.insertlinebelow", R.string.action_insert_line_below),
      ExpandedAction("ide.editor.text.selectline", R.string.action_select_line),
      ExpandedAction("ide.editor.text.trimtrailingwhitespace", R.string.action_trim_trailing_whitespace),
      ExpandedAction("ide.editor.text.indentline", R.string.action_indent_line),
      ExpandedAction("ide.editor.text.unindentline", R.string.action_unindent_line),
      ExpandedAction("ide.editor.text.joinlines", R.string.action_join_lines),
      ExpandedAction("ide.editor.text.clearall", R.string.action_clear_all),
  )
  val defaultExpandedActionIds = listOf(
      "ide.editor.text.commentline", "ide.editor.text.duplicateline",
      "ide.editor.text.deleteline", "ide.editor.text.indentline",
      "ide.editor.text.unindentline",
  )
  private val supportedIds = supportedExpandedActions.map(ExpandedAction::id).toSet()

  fun labelForActionId(context: Context, id: String): String =
      context.getString(supportedExpandedActions.first { it.id == id }.labelRes)
  val expandedActionIds: List<String>
    get() = legacyActionIds()

  /** Compatibility for the former five-action settings dialog. */
  fun setExpandedActionIds(ids: List<String>) {
    val sanitized = ids.filter { it in supportedIds }.distinct().ifEmpty { defaultExpandedActionIds }
    EditorPreferences.quickInputExpandedActionIds = JSONArray(sanitized).toString()
  }

  fun resetExpandedActionIds() {
    EditorPreferences.quickInputExpandedActionIds = null
  }

  fun textTypeFor(file: File?): QuickInputTextType = when (file?.extension) {
    "java", "gradle", "kt", "kts" -> QuickInputTextType.JAVA_JVM
    "xml" -> QuickInputTextType.XML
    else -> QuickInputTextType.PLAIN_TEXT
  }

  fun itemsFor(file: File?, symbols: List<EditorQuickItem>): List<EditorQuickItem> =
      itemsFor(textTypeFor(file), symbols)

  fun itemsFor(type: QuickInputTextType, symbols: List<EditorQuickItem>): List<EditorQuickItem> {
    val stored = readProfile(type) ?: return defaultItems(symbols)
    return stored.take(MAX_ITEMS).mapNotNull(::toQuickItem).ifEmpty { defaultItems(symbols) }
  }

  fun customizationItems(type: QuickInputTextType): List<StoredItem> {
    return readProfile(type) ?: defaultStoredItems(type)
  }

  fun saveItems(type: QuickInputTextType, items: List<StoredItem>) {
    val profiles = readProfiles()
    profiles.put(type.name, JSONArray().apply { items.take(MAX_ITEMS).forEach { put(it.toJson()) } })
    EditorPreferences.quickInputProfiles = JSONObject().put("profiles", profiles).toString()
  }

  fun resetItems(type: QuickInputTextType) {
    val profiles = readProfiles()
    profiles.remove(type.name)
    EditorPreferences.quickInputProfiles = JSONObject().put("profiles", profiles).toString()
  }

  data class StoredItem(val id: String, val label: String, val text: String? = null, val offset: Int = 0, val actionId: String? = null) {
    fun toJson() = JSONObject().put("id", id).put("label", label).apply {
      if (actionId != null) put("actionId", actionId) else put("text", text).put("offset", offset)
    }
  }

  fun newItemId(): String = "custom:${UUID.randomUUID()}"

  fun insertion(label: String, text: String, offset: Int) = StoredItem(newItemId(), label, text, offset)
  fun action(label: String, actionId: String) = StoredItem(newItemId(), label, actionId = actionId)

  private fun defaultItems(symbols: List<EditorQuickItem>) =
      (symbols + legacyActionIds().map(::defaultAction)).take(MAX_ITEMS)

  private fun defaultStoredItems(type: QuickInputTextType): List<StoredItem> {
    val symbols = when (type) {
      QuickInputTextType.PLAIN_TEXT -> Symbols.plainTextSymbols
      QuickInputTextType.JAVA_JVM -> Symbols.forQuickInputTextType(type)
      QuickInputTextType.XML -> Symbols.forQuickInputTextType(type)
    }
    return (symbols.map { StoredItem("builtin:${it.label}:${it.commit}:${it.offset}", it.label, it.commit, it.offset) } +
        legacyActionIds().map { id -> StoredItem("action:$id", defaultAction(id).label.toString(), actionId = id) })
        .take(MAX_ITEMS)
  }

  private fun legacyActionIds(): List<String> {
    val raw = EditorPreferences.quickInputExpandedActionIds ?: return defaultExpandedActionIds
    return runCatching { JSONArray(raw).let { array -> List(array.length()) { array.getString(it) } } }
        .getOrDefault(defaultExpandedActionIds).filter { it in supportedIds }.distinct().ifEmpty { defaultExpandedActionIds }
  }

  private fun defaultAction(id: String) = when (id) {
    "ide.editor.text.commentline" -> ActionQuickItem("action:comment-line", "Com", id)
    "ide.editor.text.duplicateline" -> ActionQuickItem("action:duplicate-line", "Dup", id)
    "ide.editor.text.deleteline" -> ActionQuickItem("action:delete-line", "Del", id)
    "ide.editor.text.indentline" -> ActionQuickItem("action:indent-line", "Ind", id)
    else -> ActionQuickItem("action:unindent-line", "Out", id)
  }

  private fun toQuickItem(item: StoredItem): EditorQuickItem? = when {
    item.actionId in supportedIds -> ActionQuickItem(item.id, item.label, item.actionId!!)
    item.actionId == null && item.text != null && item.offset in 0..item.text.length -> SymbolQuickItem(item.id, item.label, item.text, item.offset)
    else -> null
  }
  private fun readProfiles(): JSONObject = runCatching { JSONObject(EditorPreferences.quickInputProfiles ?: "{}").optJSONObject("profiles") ?: JSONObject() }.getOrDefault(JSONObject())
  private fun readProfile(type: QuickInputTextType): List<StoredItem>? {
    val array = readProfiles().optJSONArray(type.name) ?: return null
    return List(array.length()) { i -> array.optJSONObject(i) }.mapNotNull { o -> o?.let { StoredItem(it.optString("id"), it.optString("label"), it.optString("text").takeIf(String::isNotEmpty), it.optInt("offset"), it.optString("actionId").takeIf(String::isNotEmpty)) } }
  }
}