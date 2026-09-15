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
import com.tom.rv2ide.artificial.catalog.LocalLlmSettings
import com.tom.rv2ide.artificial.catalog.ModelRepository
import com.tom.rv2ide.preferences.internal.prefManager

/**
 * Configures the Local LLM provider from the AI Agent preferences page.
 *
 * The layout, validation, fetch and save flows come from [ProviderConfigDialog]; only the storage
 * locations are specific to this provider. Its model list is not hard-coded at all — local servers
 * decide which models they serve, so the list is fetched from the server's own `/v1/models`
 * endpoint on demand. Until then the stored value is shown, so the dialog is usable without a
 * running server.
 */
class LocalLLMDialog : ProviderConfigDialog() {

    override val providerId = LocalLlmSettings.PROVIDER_ID

    override val titleRes = R.string.local_llm_config_title

    override val apiKeyHintRes = R.string.local_llm_api_key

    override val apiKeyKey = LocalLlmSettings.API_KEY_KEY

    /** This is the provider whose endpoint the user supplies, so the row is shown here. */
    override val baseUrlHintRes = R.string.local_llm_base_url

    /** Local servers are commonly unauthenticated, so an empty key must not block saving. */
    override val isApiKeyRequired = false

    override fun readBaseUrl(): String? =
        prefManager.getString(LocalLlmSettings.BASE_URL_KEY, LocalLlmSettings.DEFAULT_BASE_URL)

    override fun storeBaseUrl(value: String) {
        prefManager.putString(LocalLlmSettings.BASE_URL_KEY, value)
    }

    /**
     * The name configured on this page, which is where the provider reads its model from.
     *
     * The generic placeholder is reported as "nothing stored" so the fetch/cache path can offer a
     * name the server has actually reported: the placeholder is a usable fallback but it is not a
     * real model, and there is no way to guess what a local server serves.
     */
    override fun readModel(): String? =
        LocalLlmSettings.model()?.takeIf { it != LocalLlmSettings.DEFAULT_MODEL }

    /**
     * Fetches from the URL being edited rather than the stored one.
     *
     * The base implementation would query the remembered endpoint, which silently lists a
     * different server's models while the user is pointing at another one.
     */
    override suspend fun fetchCatalogue(
        baseUrl: String?,
        apiKey: String?
    ): ModelRepository.RefreshResult = ModelRepository.fetchLocalModels(baseUrl.orEmpty(), apiKey)

    companion object {
        /** Tag for `show`; also keeps the dialog recoverable across configuration changes. */
        const val TAG = "local_llm_dialog"
    }
}