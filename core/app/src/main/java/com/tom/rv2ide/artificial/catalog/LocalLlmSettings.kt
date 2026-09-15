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

package com.tom.rv2ide.artificial.catalog

import com.tom.rv2ide.app.BaseApplication

/**
 * The persisted configuration of the self-hosted provider.
 *
 * Local LLM is the one provider whose endpoint and model name are entered by the user, so its
 * settings are read from three places — the request path, the model catalogue and the preferences
 * dialog. Keeping the keys here is what stops those three from disagreeing; the literals used to be
 * repeated in each of them.
 */
internal object LocalLlmSettings {

    const val PROVIDER_ID = "localllm"

    const val BASE_URL_KEY = "local_llm_base_url"
    const val MODEL_KEY = "local_llm_model_name"
    const val API_KEY_KEY = "local_llm_api_key"

    /** Same values the string resources offer as placeholders. */
    const val DEFAULT_BASE_URL = "http://localhost:1234"
    const val DEFAULT_MODEL = "local-model"

    /**
     * Endpoint as configured, without a trailing slash so callers can append a path directly.
     * `null` when it has not been configured, which the provider treats as "not usable yet".
     */
    fun baseUrl(): String? = BaseApplication.getBaseInstance()
        .prefManager
        .getString(BASE_URL_KEY, null)
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.isNotEmpty() }

    /** API key, or `null` when the server is unauthenticated (the common case). */
    fun apiKey(): String? = BaseApplication.getBaseInstance()
        .prefManager
        .getString(API_KEY_KEY, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
