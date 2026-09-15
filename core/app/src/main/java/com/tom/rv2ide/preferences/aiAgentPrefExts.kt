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
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.catalog.LocalLlmSettings
import com.tom.rv2ide.artificial.dialogs.AnthropicConfigDialog
import com.tom.rv2ide.artificial.dialogs.DeepSeekConfigDialog
import com.tom.rv2ide.artificial.dialogs.GeminiConfigDialog
import com.tom.rv2ide.artificial.dialogs.GrokConfigDialog
import com.tom.rv2ide.artificial.dialogs.LocalLLMDialog
import com.tom.rv2ide.artificial.dialogs.OpenAIConfigDialog
import com.tom.rv2ide.artificial.dialogs.ProviderConfigDialog
import com.tom.rv2ide.artificial.secrets.ApiKey
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
  @IgnoredOnParcel private var localLlmPref: LocalLlmConfig? = null

  init {
    val aiAgentEnabled = AIAgentEnabled { isEnabled -> updateApiKeyPreferencesState(isEnabled) }

    geminiApiKeyPref = GeminiApiKey()
    deepseekApiKeyPref = DeepseekApiKey()
    openAIApiKeyPref = OpenAIApiKey()
    anthropicApiKeyPref = AnthropicApiKey()
    grokApiKeyPref = GrokApiKey()
    localLlmPref = LocalLlmConfig()

    addPreference(aiAgentEnabled)
    addPreference(anthropicApiKeyPref!!)
    addPreference(deepseekApiKeyPref!!)
    addPreference(geminiApiKeyPref!!)
    addPreference(openAIApiKeyPref!!)
    addPreference(grokApiKeyPref!!)
    addPreference(localLlmPref!!)
  }

  private fun updateApiKeyPreferencesState(isEnabled: Boolean) {
    geminiApiKeyPref?.setEnabled(isEnabled)
    deepseekApiKeyPref?.setEnabled(isEnabled)
    openAIApiKeyPref?.setEnabled(isEnabled)
    anthropicApiKeyPref?.setEnabled(isEnabled)
    grokApiKeyPref?.setEnabled(isEnabled)
    localLlmPref?.setEnabled(isEnabled)
  }
}

/**
 * Shows a provider configuration dialog from a preference entry.
 *
 * A hosting [FragmentActivity] is required to show a DialogFragment; without one there is simply
 * nowhere to display the editor, so the click is consumed and nothing happens.
 *
 * [refreshSummary] runs after the dialog has actually written its values — refreshing on click
 * would read the old ones, since the dialog is shown asynchronously.
 */
private fun Context.showProviderConfigDialog(
    dialog: ProviderConfigDialog,
    tag: String,
    refreshSummary: () -> Unit,
) {
  val host = findFragmentActivity() ?: return
  dialog
      .apply { onSaved = refreshSummary }
      .show(host.supportFragmentManager, tag)
}


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
    override val key: String = ApiKey.GROK_KEY,
    override val title: Int = R.string.ai_agent_grok_api_key,
    override val icon: Int = R.drawable.ic_ai_grok,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = ApiKey.GROK_KEY
          title = context.getString(R.string.ai_agent_grok_api_key)
          summary = summaryText(context)
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    preference.context.showProviderConfigDialog(
        GrokConfigDialog(),
        GrokConfigDialog.TAG,
    ) { preference.summary = summaryText(preference.context) }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun summaryText(context: Context): String {
    val apiKey = prefManager.getString(ApiKey.GROK_KEY, "")
    val display = if (apiKey.length > 12) apiKey.take(12) + "…" else apiKey
    return if (apiKey.isBlank()) context.getString(R.string.ai_agent_click_to_set_api_key) else context.getString(R.string.ai_agent_api_key_masked, display)
  }
}

@Parcelize
private class GeminiApiKey(
    override val key: String = ApiKey.GEMINI_KEY,
    override val title: Int = R.string.ai_agent_api_key,
    override val icon: Int = R.drawable.ic_ai_gemini,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = ApiKey.GEMINI_KEY
          title = context.getString(R.string.ai_agent_api_key)
          summary = summaryText(context)
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    preference.context.showProviderConfigDialog(
        GeminiConfigDialog(),
        GeminiConfigDialog.TAG,
    ) { preference.summary = summaryText(preference.context) }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun summaryText(context: Context): String {
    val apiKey = prefManager.getString(ApiKey.GEMINI_KEY, "")
    val display = if (apiKey.length > 12) apiKey.take(12) + "…" else apiKey
    return if (apiKey.isBlank()) context.getString(R.string.ai_agent_click_to_set_api_key) else context.getString(R.string.ai_agent_api_key_masked, display)
  }
}

@Parcelize
private class DeepseekApiKey(
    override val key: String = ApiKey.DEEPSEEK_KEY,
    override val title: Int = R.string.ai_agent_deepseek_api_key,
    override val icon: Int = R.drawable.ic_ai_deepseek,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = ApiKey.DEEPSEEK_KEY
          title = context.getString(R.string.ai_agent_deepseek_api_key)
          summary = summaryText(context)
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    preference.context.showProviderConfigDialog(
        DeepSeekConfigDialog(),
        DeepSeekConfigDialog.TAG,
    ) { preference.summary = summaryText(preference.context) }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun summaryText(context: Context): String {
    val apiKey = prefManager.getString(ApiKey.DEEPSEEK_KEY, "")
    val display = if (apiKey.length > 12) apiKey.take(12) + "…" else apiKey
    return if (apiKey.isBlank()) context.getString(R.string.ai_agent_click_to_set_api_key) else context.getString(R.string.ai_agent_api_key_masked, display)
  }
}

@Parcelize
private class OpenAIApiKey(
    override val key: String = ApiKey.OPENAI_KEY,
    override val title: Int = R.string.ai_agent_openai_api_key,
    override val icon: Int = R.drawable.ic_ai_gpt,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = ApiKey.OPENAI_KEY
          title = context.getString(R.string.ai_agent_openai_api_key)
          summary = summaryText(context)
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    preference.context.showProviderConfigDialog(
        OpenAIConfigDialog(),
        OpenAIConfigDialog.TAG,
    ) { preference.summary = summaryText(preference.context) }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun summaryText(context: Context): String {
    val apiKey = prefManager.getString(ApiKey.OPENAI_KEY, "")
    val display = if (apiKey.length > 12) apiKey.take(12) + "…" else apiKey
    return if (apiKey.isBlank()) context.getString(R.string.ai_agent_click_to_set_api_key) else context.getString(R.string.ai_agent_api_key_masked, display)
  }
}

@Parcelize
private class AnthropicApiKey(
    override val key: String = ApiKey.ANTHROPIC_KEY,
    override val title: Int = R.string.ai_agent_anthropic_api_key,
    override val icon: Int = R.drawable.ic_ai_anthropic,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = ApiKey.ANTHROPIC_KEY
          title = context.getString(R.string.ai_agent_anthropic_api_key)
          summary = summaryText(context)
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    preference.context.showProviderConfigDialog(
        AnthropicConfigDialog(),
        AnthropicConfigDialog.TAG,
    ) { preference.summary = summaryText(preference.context) }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun summaryText(context: Context): String {
    val apiKey = prefManager.getString(ApiKey.ANTHROPIC_KEY, "")
    val display = if (apiKey.length > 12) apiKey.take(12) + "…" else apiKey
    return if (apiKey.isBlank()) context.getString(R.string.ai_agent_click_to_set_api_key) else context.getString(R.string.ai_agent_api_key_masked, display)
  }
}

/**
 * Opens the Local LLM configuration dialog.
 *
 * Unlike the cloud providers this entry does not store a single secret: the endpoint, the optional
 * key and the model name are all entered in the dialog itself.
 */
@Parcelize
private class LocalLlmConfig(
    override val key: String = "idepref_ai_agent_local_llm",
    override val title: Int = R.string.ai_agent_local_llm,
    override val icon: Int = R.drawable.ic_ai_local,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "idepref_ai_agent_local_llm"
          title = context.getString(R.string.ai_agent_local_llm)
          summary = summaryText(context)
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    preference.context.showProviderConfigDialog(
        LocalLLMDialog(),
        LocalLLMDialog.TAG,
    ) { preference.summary = summaryText(preference.context) }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  /**
   * Endpoint plus model name, which is what makes this provider usable. When nothing is configured
   * yet it points at what has to be filled in rather than at the API key the other entries expect,
   * since the endpoint is the only required field here.
   */
  private fun summaryText(context: Context): String {
    val baseUrl = LocalLlmSettings.baseUrl()
        ?: return context.getString(R.string.ai_agent_local_llm_summary)
    val model = prefManager.getString(LocalLlmSettings.MODEL_KEY, null).orEmpty()
    return if (model.isBlank()) baseUrl else "$baseUrl · $model"
  }
}

/**
 * Finds the [FragmentActivity] hosting [this] context.
 *
 * Preference views are created with a themed context wrapper, so the preference's own context is
 * not the activity and cannot be cast to one.
 */
private fun Context.findFragmentActivity(): FragmentActivity? {
  var context: Context? = this
  while (context is ContextWrapper) {
    if (context is FragmentActivity) return context
    context = context.baseContext
  }
  return null
}
