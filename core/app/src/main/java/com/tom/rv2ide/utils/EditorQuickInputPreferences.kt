package com.tom.rv2ide.utils

import com.tom.rv2ide.models.ActionQuickItem
import com.tom.rv2ide.models.EditorQuickItem
import com.tom.rv2ide.models.SymbolQuickItem
import com.tom.rv2ide.preferences.QuickInputTextType
import com.tom.rv2ide.preferences.internal.EditorPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Persisted, per-text-type expanded quick-input items. */
object EditorQuickInputPreferences {
  const val MAX_ITEMS = 30

  data class ExpandedAction(val id: String, val label: String)
  val supportedExpandedActions = listOf(
      ExpandedAction("ide.editor.text.uppercase", "Convert to uppercase"),
      ExpandedAction("ide.editor.text.lowercase", "Convert to lowercase"),
      ExpandedAction("ide.editor.text.deleteline", "Delete line"),
      ExpandedAction("ide.editor.text.duplicateline", "Duplicate line"),
      ExpandedAction("ide.editor.text.copyline", "Copy line"),
      ExpandedAction("ide.editor.text.commentline", "Comment line"),
      ExpandedAction("ide.editor.text.movelineup", "Move line up"),
      ExpandedAction("ide.editor.text.movelinedown", "Move line down"),
      ExpandedAction("ide.editor.text.insertlineabove", "Insert line above"),
      ExpandedAction("ide.editor.text.insertlinebelow", "Insert line below"),
      ExpandedAction("ide.editor.text.selectline", "Select line"),
      ExpandedAction("ide.editor.text.trimtrailingwhitespace", "Trim trailing whitespace"),
      ExpandedAction("ide.editor.text.indentline", "Indent line"),
      ExpandedAction("ide.editor.text.unindentline", "Unindent line"),
      ExpandedAction("ide.editor.text.joinlines", "Join lines"),
      ExpandedAction("ide.editor.text.clearall", "Clear all"),
  )
  val defaultExpandedActionIds = listOf(
      "ide.editor.text.commentline", "ide.editor.text.duplicateline",
      "ide.editor.text.deleteline", "ide.editor.text.indentline",
      "ide.editor.text.unindentline",
  )
  private val supportedIds = supportedExpandedActions.map(ExpandedAction::id).toSet()

  fun labelForActionId(id: String) = supportedExpandedActions.first { it.id == id }.label
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