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

package com.tom.rv2ide.preferences

import android.content.Context
import androidx.preference.Preference
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.R
import com.tom.rv2ide.preferences.internal.prefManager
import com.tom.rv2ide.resources.R.string
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/** * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null */
@Parcelize
class AIAgentPreferencesScreen(
    override val key: String = "idepref_ai_agent",
    override val title: Int = string.ai_agent_title,
    override val summary: Int? = string.ai_agent_description,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(AIAgentConfig())
  }
}

@Parcelize
private class AIAgentConfig(
    override val key: String = "idepref_ai_agent_config",
    override val title: Int = string.ai_agent_title,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  @IgnoredOnParcel private var geminiApiKeyPref: GeminiApiKey? = null
  @IgnoredOnParcel private var deepseekApiKeyPref: DeepseekApiKey? = null
  @IgnoredOnParcel private var openAIApiKeyPref: OpenAIApiKey? = null
  @IgnoredOnParcel private var anthropicApiKeyPref: AnthropicApiKey? = null
  @IgnoredOnParcel private var grokApiKeyPref: GrokApiKey? = null

  init {
    val aiAgentEnabled = AIAgentEnabled { isEnabled -> updateApiKeyPreferencesState(isEnabled) }

    geminiApiKeyPref = GeminiApiKey()
    deepseekApiKeyPref = DeepseekApiKey()
    openAIApiKeyPref = OpenAIApiKey()
    anthropicApiKeyPref = AnthropicApiKey()
    grokApiKeyPref = GrokApiKey()

    addPreference(aiAgentEnabled)
    addPreference(anthropicApiKeyPref!!)
    addPreference(deepseekApiKeyPref!!)
    addPreference(geminiApiKeyPref!!)
    addPreference(openAIApiKeyPref!!)
    addPreference(grokApiKeyPref!!)
  }

  private fun updateApiKeyPreferencesState(isEnabled: Boolean) {
    geminiApiKeyPref?.setEnabled(isEnabled)
    deepseekApiKeyPref?.setEnabled(isEnabled)
    openAIApiKeyPref?.setEnabled(isEnabled)
    anthropicApiKeyPref?.setEnabled(isEnabled)
    grokApiKeyPref?.setEnabled(isEnabled)
  }
}

private fun buildApiKeyInput(context: Context, currentValue: String, labelRes: Int): TextInputLayout {
  val inputContext =
      android.view.ContextThemeWrapper(
          context,
          com.google.android.material.R.style.Theme_Material3_DayNight,
      )
  val inputLayout =
      TextInputLayout(
          inputContext,
          null,
          com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox,
      )
  inputLayout.hint = context.getString(labelRes)
  inputLayout.setPadding(dp(context, 24), dp(context, 8), dp(context, 24), 0)
  val editText = android.widget.EditText(inputContext)
  editText.setText(currentValue)
  inputLayout.addView(editText)
  return inputLayout
}

/**
 * Fallback summary when no Context is available.
 * Cannot use localized resources here, so a neutral English placeholder is used.
 */
private fun getFallbackSummary(apiKey: String): String =
    if (apiKey.isBlank()) "API Key" else apiKey

private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()


@Parcelize
private class AIAgentEnabled(
    override val key: String = "ai_agent_enabled",
    override val title: Int = R.string.ai_agent_enable,
    override val icon: Int = R.drawable.ic_ai_agent,
    @IgnoredOnParcel private val onStateChanged: ((Boolean) -> Unit)? = null,
) :
    SwitchPreference(
        setValue = { isEnabled ->
          prefManager.putBoolean("ai_agent_enabled", isEnabled)
          onStateChanged?.invoke(isEnabled)
        },
        getValue = { prefManager.getBoolean("ai_agent_enabled", false) },
    ) {

  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).apply {
      key = "ai_agent_enabled"
      title = context.getString(R.string.ai_agent_enable)
      summary = context.getString(R.string.ai_agent_enable_summary)
    }
  }
}


@Parcelize
private class GrokApiKey(
    override val key: String = "ai_agent_grok_api_key",
    override val title: Int = R.string.ai_agent_grok_api_key,
    override val icon: Int = R.drawable.ic_ai_grok,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "ai_agent_grok_api_key"
          title = context.getString(R.string.ai_agent_grok_api_key)
          summary = getSummaryText()
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val inputLayout =
        buildApiKeyInput(
            context,
            prefManager.getString("ai_agent_grok_api_key", ""),
            R.string.ai_agent_grok_api_key_label,
        )
    val editText = inputLayout.editText!!

    val dialog =
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_agent_grok_api_key_dialog_title)
            .setView(inputLayout)
            .setPositiveButton(R.string.action_save) { _, _ ->
              val apiKey = editText.text.toString().trim()
              prefManager.putString("ai_agent_grok_api_key", apiKey)
              preference.summary = getSummaryText()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()

    dialog.show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun getSummaryText(): String {
    val apiKey = prefManager.getString("ai_agent_grok_api_key", "")
    val context = preference?.context ?: return getFallbackSummary(apiKey)
    return if (apiKey.isBlank()) context.getString(R.string.ai_agent_click_to_set_api_key) else context.getString(R.string.ai_agent_api_key_masked, apiKey.take(8))
  }
}

@Parcelize
private class GeminiApiKey(
    override val key: String = "ai_agent_gemini_api_key",
    override val title: Int = R.string.ai_agent_api_key,
    override val icon: Int = R.drawable.ic_ai_gemini,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "ai_agent_gemini_api_key"
          title = context.getString(R.string.ai_agent_api_key)
          summary = getSummaryText()
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val inputLayout = buildApiKeyInput(
        context,
        prefManager.getString("ai_agent_gemini_api_key", ""),
        R.string.ai_agent_api_key_label,
    )
    val editText = inputLayout.editText!!

    val dialog =
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_agent_api_key_dialog_title)
            .setView(inputLayout)
            .setPositiveButton(R.string.action_save) { _, _ ->
              val apiKey = editText.text.toString().trim()
              prefManager.putString("ai_agent_gemini_api_key", apiKey)
              preference.summary = getSummaryText()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()

    dialog.show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun getSummaryText(): String {
    val apiKey = prefManager.getString("ai_agent_gemini_api_key", "")
    val context = preference?.context ?: return getFallbackSummary(apiKey)
    return if (apiKey.isBlank()) context.getString(R.string.ai_agent_click_to_set_api_key) else context.getString(R.string.ai_agent_api_key_masked, apiKey.take(8))
  }
}

@Parcelize
private class DeepseekApiKey(
    override val key: String = "ai_agent_deepseek_api_key",
    override val title: Int = R.string.ai_agent_deepseek_api_key,
    override val icon: Int = R.drawable.ic_ai_deepseek,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "ai_agent_deepseek_api_key"
          title = context.getString(R.string.ai_agent_deepseek_api_key)
          summary = getSummaryText()
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val inputLayout = buildApiKeyInput(
        context,
        prefManager.getString("ai_agent_deepseek_api_key", ""),
        R.string.ai_agent_deepseek_api_key_label,
    )
    val editText = inputLayout.editText!!

    val dialog =
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_agent_deepseek_api_key_dialog_title)
            .setView(inputLayout)
            .setPositiveButton(R.string.action_save) { _, _ ->
              val apiKey = editText.text.toString().trim()
              prefManager.putString("ai_agent_deepseek_api_key", apiKey)
              preference.summary = getSummaryText()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()

    dialog.show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun getSummaryText(): String {
    val apiKey = prefManager.getString("ai_agent_deepseek_api_key", "")
    val context = preference?.context ?: return getFallbackSummary(apiKey)
    return if (apiKey.isBlank()) context.getString(R.string.ai_agent_click_to_set_api_key) else context.getString(R.string.ai_agent_api_key_masked, apiKey.take(8))
  }
}

@Parcelize
private class OpenAIApiKey(
    override val key: String = "ai_agent_openai_api_key",
    override val title: Int = R.string.ai_agent_openai_api_key,
    override val icon: Int = R.drawable.ic_ai_gpt,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "ai_agent_openai_api_key"
          title = context.getString(R.string.ai_agent_openai_api_key)
          summary = getSummaryText()
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val inputLayout = buildApiKeyInput(
        context,
        prefManager.getString("ai_agent_openai_api_key", ""),
        R.string.ai_agent_openai_api_key_label,
    )
    val editText = inputLayout.editText!!

    val dialog =
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_agent_openai_api_key_dialog_title)
            .setView(inputLayout)
            .setPositiveButton(R.string.action_save) { _, _ ->
              val apiKey = editText.text.toString().trim()
              prefManager.putString("ai_agent_openai_api_key", apiKey)
              preference.summary = getSummaryText()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()

    dialog.show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun getSummaryText(): String {
    val apiKey = prefManager.getString("ai_agent_openai_api_key", "")
    val context = preference?.context ?: return getFallbackSummary(apiKey)
    return if (apiKey.isBlank()) context.getString(R.string.ai_agent_click_to_set_api_key) else context.getString(R.string.ai_agent_api_key_masked, apiKey.take(8))
  }
}

@Parcelize
private class AnthropicApiKey(
    override val key: String = "ai_agent_anthropic_api_key",
    override val title: Int = R.string.ai_agent_anthropic_api_key,
    override val icon: Int = R.drawable.ic_ai_anthropic,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "ai_agent_anthropic_api_key"
          title = context.getString(R.string.ai_agent_anthropic_api_key)
          summary = getSummaryText()
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val inputLayout = buildApiKeyInput(
        context,
        prefManager.getString("ai_agent_anthropic_api_key", ""),
        R.string.ai_agent_anthropic_api_key_label,
    )
    val editText = inputLayout.editText!!

    val dialog =
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_agent_anthropic_api_key_dialog_title)
            .setView(inputLayout)
            .setPositiveButton(R.string.action_save) { _, _ ->
              val apiKey = editText.text.toString().trim()
              prefManager.putString("ai_agent_anthropic_api_key", apiKey)
              preference.summary = getSummaryText()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()

    dialog.show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun getSummaryText(): String {
    val apiKey = prefManager.getString("ai_agent_anthropic_api_key", "")
    val context = preference?.context ?: return getFallbackSummary(apiKey)
    return if (apiKey.isBlank()) context.getString(R.string.ai_agent_click_to_set_api_key) else context.getString(R.string.ai_agent_api_key_masked, apiKey.take(8))
  }
}
