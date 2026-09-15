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
import com.tom.rv2ide.artificial.agents.openai.OpenAI
import com.tom.rv2ide.artificial.secrets.ApiKey

/**
 * Configures the OpenAI provider from the AI Agent preferences page.
 *
 * Credentials, then the model to use, with the list fetched from the provider rather than
 * hard-coded; the flow itself lives in [ProviderConfigDialog].
 *
 * The endpoint is deliberately absent: OpenAI is reached at a fixed URL, so the row stays hidden
 * rather than offering a setting that would be ignored.
 */
class OpenAIConfigDialog : ProviderConfigDialog() {

    override val providerId = OpenAI.PROVIDER_ID

    override val titleRes = R.string.ai_agent_openai_api_key_dialog_title

    override val apiKeyHintRes = R.string.ai_agent_openai_api_key_label

    override val apiKeyKey = ApiKey.OPENAI_KEY

    /**
     * Read through [ApiKey] rather than from [apiKeyKey] directly, so the two cannot disagree about
     * which entry holds this provider's key.
     */
    override fun readApiKey(): String? = ApiKey.getOpenAIApiKey().takeIf { it.isNotBlank() }

    companion object {
        /** Tag for `show`; also keeps the dialog recoverable across configuration changes. */
        const val TAG = "openai_config_dialog"
    }
}