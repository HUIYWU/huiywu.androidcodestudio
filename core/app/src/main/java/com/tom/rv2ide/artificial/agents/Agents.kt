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
  private val AGENT_KEY = "ai_agent_model_name"
  private val PROVIDER_KEY = "ai_provider_name"

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
   * Stores the model together with the provider it belongs to.
   *
   * The provider is a parameter rather than something inferred from the model name. Inference
   * cannot work against a fetched catalogue — an unknown model name maps back to nothing — and the
   * old fallback silently kept the previous provider, producing a provider/model mismatch.
   */
  fun setModel(providerId: String, modelName: String) {
    sp.edit()
      .putString(PROVIDER_KEY, providerId)
      .putString(AGENT_KEY, modelName)
      .apply()
  }

  fun getAgent(): String {
    val savedModel = sp.getString(AGENT_KEY, null)
    if (savedModel != null) return savedModel

    return getDefaultModelForProvider(getProvider())
  }

  fun setProvider(provider: String) {
    sp.edit().putString(PROVIDER_KEY, provider).apply()
  }

  fun getProvider(): String {
    return sp.getString(PROVIDER_KEY, "gemini") ?: "gemini"
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
}