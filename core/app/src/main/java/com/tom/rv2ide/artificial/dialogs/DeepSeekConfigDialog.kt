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

package com.tom.rv2ide.artificial.dialogs

import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.agents.deepseek.DeepSeek
import com.tom.rv2ide.artificial.secrets.ApiKey
import com.tom.rv2ide.preferences.internal.prefManager

/**
 * Configures the DeepSeek provider from the AI Agent preferences page.
 *
 * The same three steps as the Local LLM editor — credentials, then the model to use — with the
 * model list fetched from the provider itself instead of being hard-coded.
 *
 * The endpoint is deliberately absent: DeepSeek is reached at a fixed URL, so the row is hidden
 * rather than shown as a setting that would be ignored.
 *
 * The model is stored through [Agents], which is where the provider reads its selection from.
 */
class DeepSeekConfigDialog : ModelConfigDialog() {

    override val providerId = DeepSeek.PROVIDER_ID

    override val titleRes = R.string.ai_agent_deepseek_api_key_dialog_title

    override val apiKeyHintRes = R.string.ai_agent_deepseek_api_key_label

    /** Read through [ApiKey] so the entry exists in one place only. */
    override fun readApiKey(): String? = ApiKey.getDeepseekApiKey().takeIf { it.isNotBlank() }

    override fun storeApiKey(value: String) {
        prefManager.putString(API_KEY_KEY, value)
    }

    override fun storeModel(value: String) {
        val agents = Agents(requireContext())
        // The model selection is stored next to the active provider, so writing it would otherwise
        // switch the provider as a side effect of editing this dialog's key.
        val previousProvider = agents.getProvider()
        agents.setModel(providerId, value)
        if (previousProvider != providerId) {
            agents.setProvider(previousProvider)
        }
    }

    companion object {
        /** Tag for `show`; also keeps the dialog recoverable across configuration changes. */
        const val TAG = "deepseek_config_dialog"

        /**
         * Preference entry holding the key.
         *
         * [ApiKey] exposes a getter only, so the name is repeated here for the write; reading still
         * goes through [ApiKey] so the two cannot drift apart unnoticed.
         */
        private const val API_KEY_KEY = "ai_agent_deepseek_api_key"
    }
}
