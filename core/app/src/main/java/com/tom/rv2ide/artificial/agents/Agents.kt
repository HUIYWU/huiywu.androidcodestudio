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

package com.tom.rv2ide.artificial.agents

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import com.tom.rv2ide.artificial.catalog.LocalLlmSettings
import com.tom.rv2ide.artificial.catalog.ModelRepository

/**
 * Persists the selected provider/model and exposes the model catalogue.
 *
 * The catalogue itself lives in [ModelRepository] (fetched from the provider, cached, with a
 * bundled fallback). This class deliberately keeps no copy of it: while the lists were hard-coded
 * here they drifted from the defaults the providers used, and retired model names stayed behind in
 * both places.
 */
class Agents(ctx: Context) {

  private val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)
  private val PROVIDER_KEY = "ai_provider_name"

  /** Entry every provider's selection used to share, before models were stored per provider. */
  private val LEGACY_AGENT_KEY = "ai_agent_model_name"

  init {
    // Adopt the shared entry, once, for the provider it actually described: it was written by
    // whichever provider was active, so it belongs to the persisted one. Without this every upgrade
    // would silently drop the user's selected model and fall back to the provider default.
    //
    // Local LLM is skipped: its model has its own entry (`LocalLlmSettings`) and is already correct
    // there, so adopting the shared value would only overwrite it with whatever ran last.
    sp.getString(LEGACY_AGENT_KEY, null)?.takeIf { it.isNotBlank() }?.let { legacy ->
      val provider = getProvider()
      val edit = sp.edit()
      if (provider != LocalLlmSettings.PROVIDER_ID) {
        val key = modelKey(provider)
        if (sp.getString(key, null).isNullOrBlank()) {
          edit.putString(key, legacy)
        }
      }
      edit.remove(LEGACY_AGENT_KEY).apply()
    }
  }

  fun getModelsForProvider(providerId: String): List<String> =
    ModelRepository.getModels(providerId)

  /**
   * The provider's default model, used when nothing usable is stored.
   *
   * Providers must call this instead of embedding their own literal: those literals were exactly
   * the part that silently rotted when a provider renamed or retired a model.
   */
  fun getDefaultModelForProvider(providerId: String): String =
    ModelRepository.getDefaultModel(providerId)

  // NOTE: the former getProviderForModel(name) reverse lookup is gone on purpose. It existed only
  // to let setAgent() infer the provider from a model name, which cannot work once the catalogue is
  // fetched at runtime, and its silent `else` branch caused provider/model mismatches.

  /**
   * Model entry for one provider.
   *
   * A model name only means something for the provider that offered it, so each provider keeps its
   * own. The single shared entry this replaced was written by every provider's selection, so
   * choosing a model for Grok overwrote the one Local LLM was running on and the sidebar displayed
   * Grok's model for Local LLM.
   */
  private fun modelKey(providerId: String) = "ai_agent_model_name_$providerId"

  /** The model selected for [providerId], or `null` when none was chosen yet. */
  fun getModel(providerId: String): String? =
    if (providerId == LocalLlmSettings.PROVIDER_ID) {
      // Local LLM keeps its model in its own settings object, because the provider reads it from
      // there when building requests. Reading a second copy here would be the very divergence this
      // class now avoids, so the configuration entry is the answer for this provider.
      LocalLlmSettings.model()
    } else {
      sp.getString(modelKey(providerId), null)?.takeIf { it.isNotBlank() }
    }

  /**
   * Stores one provider's model.
   *
   * The active provider is deliberately left alone: selecting a model for a provider the user is
   * merely configuring must not switch to it as a side effect of pressing Save.
   */
  fun setModel(providerId: String, modelName: String) {
    // Local LLM's model lives in its own settings object, because the provider reads it from there
    // when building requests; writing a second copy here would be the very divergence this class
    // now avoids.
    if (providerId == LocalLlmSettings.PROVIDER_ID) {
      LocalLlmSettings.setModel(modelName)
      return
    }

    sp.edit()
      .putString(modelKey(providerId), modelName)
      .apply()
  }

  /** Model the active provider will use, falling back to its default when none was chosen. */
  fun getAgent(): String =
    getModel(getProvider()) ?: getDefaultModelForProvider(getProvider())

  fun setProvider(provider: String) {
    sp.edit().putString(PROVIDER_KEY, provider).apply()
  }

  fun getProvider(): String {
    return sp.getString(PROVIDER_KEY, "gemini") ?: "gemini"
  }

  /** History size, in characters, beyond which the older turns are summarized. */
  fun getContextCharLimit(): Int =
    sp.getInt(CONTEXT_CHAR_LIMIT_KEY, CONTEXT_CHAR_LIMIT_DEFAULT)
      .coerceIn(CONTEXT_CHAR_LIMIT_MIN, CONTEXT_CHAR_LIMIT_MAX)

  fun setContextCharLimit(limitChars: Int) {
    sp.edit()
      .putInt(
        CONTEXT_CHAR_LIMIT_KEY,
        limitChars.coerceIn(CONTEXT_CHAR_LIMIT_MIN, CONTEXT_CHAR_LIMIT_MAX)
      )
      .apply()
  }

  /**
   * Whether [modelName] is currently offered by [providerId].
   *
   * Checked against the live (or cached) catalogue rather than a bundled list, so a freshly
   * fetched model is not rejected and silently replaced by the default.
   */
  fun isValidModelForProvider(modelName: String, providerId: String): Boolean {
    return modelName in getModelsForProvider(providerId)
  }

  /**
   * The model [providerId] should currently run, replacing a stale selection.
   *
   * A stored name the provider no longer offers (renamed or retired) is substituted by the
   * provider's default and written back, so the entry stops pointing at a model the provider will
   * reject. Providers call this instead of reading [getModel] directly: the value is non-null, and
   * the fallback is only effective if it is persisted.
   *
   * Local LLM's fallback is not persisted: its model is chosen on the configuration page and need
   * not appear in its own catalogue, so rewriting it here would replace the user's server-side name.
   */
  fun resolveModel(providerId: String): String {
    val stored = getModel(providerId)
    if (stored != null && isValidModelForProvider(stored, providerId)) return stored

    val fallback = getDefaultModelForProvider(providerId)
    if (providerId != LocalLlmSettings.PROVIDER_ID) {
      setModel(providerId, fallback)
    }
    return fallback
  }

  companion object {
    /** Threshold the compression uses until the user picks another one. */
    const val CONTEXT_CHAR_LIMIT_DEFAULT = 24_000

    /** Bounds the settings slider and any stored value; the slider moves by [CONTEXT_CHAR_LIMIT_STEP]. */
    const val CONTEXT_CHAR_LIMIT_MIN = 8_000
    const val CONTEXT_CHAR_LIMIT_MAX = 64_000
    const val CONTEXT_CHAR_LIMIT_STEP = 4_000

    private const val CONTEXT_CHAR_LIMIT_KEY = "ai_context_char_limit"
  }
}