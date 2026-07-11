package com.tom.rv2ide.preferences

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.preferences.internal.EditorPreferences
import com.tom.rv2ide.resources.R.string
import com.tom.rv2ide.utils.EditorQuickInputPreferences
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

internal const val QUICK_INPUT_CUSTOMIZE_SCREEN_KEY = "idepref_editor_quickInputCustomize"
internal const val QUICK_INPUT_CUSTOMIZE_REFRESH_RESULT = "quickInputCustomizeRefresh"

enum class QuickInputTextType(val displayName: String) {
  PLAIN_TEXT("Plain text"), JAVA_JVM("Java / JVM"), XML("XML")
}

internal fun quickInputCustomizeChildren(): List<IPreference> {
  val type = selectedQuickInputTextType()
  val items = EditorQuickInputPreferences.customizationItems(type)
  return buildList {
    add(QuickInputTextTypeSelector())
    add(ResetQuickInputTextType())
    addAll(items.mapIndexed { index, item -> QuickInputItemPreference(index, item) })
    add(AddQuickInputItem(items.size))
  }
}

private fun selectedQuickInputTextType(): QuickInputTextType = runCatching {
  QuickInputTextType.valueOf(EditorPreferences.quickInputCustomizeTextType)
}.getOrDefault(QuickInputTextType.PLAIN_TEXT)

@Parcelize
private class QuickInputTextTypeSelector(
    override val key: String = "idepref_editor_quickInputTextType",
    override val title: Int = string.idepref_editor_quickInputTextType_title,
    override val summary: Int? = string.idepref_editor_quickInputTextType_summary,
) : SingleChoicePreference() {
  override fun getEntries(preference: Preference) = QuickInputTextType.entries.map {
    PreferenceChoices.Entry(it.displayName, it == selectedQuickInputTextType(), it)
  }.toTypedArray()

  override fun onChoiceConfirmed(preference: Preference, entry: PreferenceChoices.Entry?, position: Int) {
    val type = entry?.data as? QuickInputTextType ?: return
    EditorPreferences.quickInputCustomizeTextType = type.name
    requestPageRefresh(preference.context)
  }
}

@Parcelize
private class ResetQuickInputTextType(
    override val key: String = "idepref_editor_quickInputResetType",
    override val title: Int = string.idepref_editor_quickInputReset_title,
    override val summary: Int? = string.idepref_editor_quickInputReset_summary,
) : BasePreference() {
  override fun onCreatePreference(context: Context) = Preference(context)
  override fun onPreferenceClick(preference: Preference): Boolean {
    MaterialAlertDialogBuilder(preference.context)
        .setTitle(string.idepref_editor_quickInputReset_title)
        .setMessage(string.idepref_editor_quickInputReset_confirm)
        .setPositiveButton(string.reset) { _, _ ->
          EditorQuickInputPreferences.resetItems(selectedQuickInputTextType())
          requestPageRefresh(preference.context)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    return true
  }
}

@Parcelize
private class QuickInputItemPreference(
    private val index: Int,
    private val item: @RawValue EditorQuickInputPreferences.StoredItem,
    override val key: String = "idepref_editor_quickInputItem:${item.id}",
    override val title: Int = string.idepref_editor_quickInputItem_title,
) : IPreference() {
  override fun onCreateView(context: Context): Preference = Preference(context).apply {
    key = this@QuickInputItemPreference.key
    title = item.label
    summary = item.actionId?.let { EditorQuickInputPreferences.labelForActionId(context, it) } ?: item.text.orEmpty()
    isIconSpaceReserved = false
    setOnPreferenceClickListener { showItemEditor(context, index, item); true }
  }
}

@Parcelize
private class AddQuickInputItem(
    private val itemCount: Int,
    override val key: String = "idepref_editor_quickInputAddItem",
    override val title: Int = string.idepref_editor_quickInputAdd_title,
    override val summary: Int? = string.idepref_editor_quickInputAdd_summary,
) : BasePreference() {
  override fun onCreatePreference(context: Context) = Preference(context)
  override fun onPreferenceClick(preference: Preference): Boolean {
    if (itemCount >= EditorQuickInputPreferences.MAX_ITEMS) {
      Toast.makeText(preference.context, string.idepref_editor_quickInputLimit_message, Toast.LENGTH_SHORT).show()
    } else showItemEditor(preference.context, null, null)
    return true
  }
}

private fun showItemEditor(context: Context, index: Int?, existing: EditorQuickInputPreferences.StoredItem?) {
  val padding = (20 * context.resources.displayMetrics.density).toInt()
  val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, 0, padding, 0) }
  val name = EditText(context).apply { hint = context.getString(string.idepref_editor_quickInputEdit_name); setText(existing?.label.orEmpty()) }
  val text = EditText(context).apply {
    hint = context.getString(string.idepref_editor_quickInputEdit_text)
    setText(existing?.let(::displayInsertionText).orEmpty()); minLines = 2
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
  }
  val functions = RadioGroup(context).apply { isVisible = false }
  val insertionId = View.generateViewId()
  functions.addView(RadioButton(context).apply {
    id = insertionId
    this.text = context.getString(string.idepref_editor_quickInputEdit_insert)
  })
  val actionIds = mutableMapOf<Int, String>()
  EditorQuickInputPreferences.supportedExpandedActions.forEach { action ->
    val id = View.generateViewId()
    actionIds[id] = action.id
    functions.addView(RadioButton(context).apply {
      this.id = id
      this.text = context.getString(action.labelRes)
    })
  }
  functions.check(actionIds.entries.firstOrNull { it.value == existing?.actionId }?.key ?: insertionId)
  val functionSelector = TextView(context).apply { isClickable = true; setPadding(0, padding, 0, padding) }
  fun updateFunctionSelection() {
    val actionId = actionIds[functions.checkedRadioButtonId]
    val selected = actionId?.let { EditorQuickInputPreferences.labelForActionId(context, it) }
        ?: context.getString(string.idepref_editor_quickInputEdit_insert)
    functionSelector.text = "${context.getString(string.idepref_editor_quickInputEdit_function)}: $selected"
    text.isVisible = actionId == null
  }
  functionSelector.setOnClickListener { functions.isVisible = !functions.isVisible }
  functions.setOnCheckedChangeListener { _, _ ->
    updateFunctionSelection()
    functions.isVisible = false
  }
  updateFunctionSelection()
  content.addView(name)
  content.addView(functionSelector)
  content.addView(functions)
  content.addView(text)
  val view = ScrollView(context).apply { addView(content, ViewGroup.LayoutParams(-1, -2)) }
  val dialog = MaterialAlertDialogBuilder(context).setTitle(string.idepref_editor_quickInputEdit_title)
      .setView(view).setPositiveButton(string.action_save, null)
      .setNegativeButton(android.R.string.cancel, null)
      .apply {
        if (existing != null) {
          setNeutralButton(string.delete) { _, _ ->
            val type = selectedQuickInputTextType()
            val items = EditorQuickInputPreferences.customizationItems(type).toMutableList()
            items.removeAt(index!!)
            EditorQuickInputPreferences.saveItems(type, items)
            requestPageRefresh(context)
          }
        }
      }
      .create()
  dialog.setOnShowListener {
    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
      val label = name.text.toString().trim()
      val actionId = actionIds[functions.checkedRadioButtonId]
      val saved = when {
        label.isEmpty() -> null
        actionId != null -> EditorQuickInputPreferences.StoredItem(existing?.id ?: EditorQuickInputPreferences.newItemId(), label, actionId = actionId)
        else -> parseInsertion(label, text.text.toString(), existing?.id)
      }
      if (saved == null || !isCompactLabel(label)) {
        name.error = context.getString(string.idepref_editor_quickInputEdit_invalid)
        return@setOnClickListener
      }
      val type = selectedQuickInputTextType()
      val items = EditorQuickInputPreferences.customizationItems(type).toMutableList()
      if (index == null) items.add(saved) else items[index] = saved
      EditorQuickInputPreferences.saveItems(type, items)
      dialog.dismiss(); requestPageRefresh(context)
    }
    }
  dialog.show()
}

private fun displayInsertionText(item: EditorQuickInputPreferences.StoredItem): String {
  val text = item.text ?: return ""
  val cursor = item.offset.coerceIn(0, text.length)
  return buildString {
    text.forEachIndexed { index, char ->
      if (index == cursor) append('|')
      if (char == '|' || char == '\\') append('\\')
      append(char)
    }
    if (cursor == text.length) append('|')
  }
}

private fun isCompactLabel(label: String): Boolean {
  return if (label.all { it.code <= 0x7f }) label.length <= 3 else label.codePointCount(0, label.length) <= 2
}

/** First unescaped | sets the optional cursor position; \| and \\ insert literal characters. */
private fun parseInsertion(label: String, raw: String, existingId: String?): EditorQuickInputPreferences.StoredItem? {
  if (raw.isEmpty()) return null
  val output = StringBuilder(); var offset: Int? = null; var escaped = false
  raw.forEach { char -> when {
    escaped && (char == '|' || char == '\\') -> { output.append(char); escaped = false }
    escaped -> { output.append('\\').append(char); escaped = false }
    char == '\\' -> escaped = true
    char == '|' && offset == null -> offset = output.length
    else -> output.append(char)
  } }
  if (escaped) output.append('\\')
  return EditorQuickInputPreferences.StoredItem(existingId ?: EditorQuickInputPreferences.newItemId(), label, output.toString(), offset ?: output.length)
}

private fun requestPageRefresh(context: Context) {
  (context as? FragmentActivity)?.supportFragmentManager?.setFragmentResult(QUICK_INPUT_CUSTOMIZE_REFRESH_RESULT, Bundle.EMPTY)
}