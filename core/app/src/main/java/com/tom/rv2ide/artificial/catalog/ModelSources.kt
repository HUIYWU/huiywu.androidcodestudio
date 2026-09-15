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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The six provider catalogues.
 *
 * Every entry follows the same shape — endpoint, credentials, id extraction, filter — and the
 * differences are exactly the ones the providers force on us:
 *
 * | provider  | endpoint                          | credentials                       |
 * |-----------|-----------------------------------|-----------------------------------|
 * | openai    | `api.openai.com/v1/models`        | `Authorization: Bearer`           |
 * | deepseek  | `api.deepseek.com/models`         | `Authorization: Bearer`           |
 * | grok      | `api.x.ai/v1/models`              | `Authorization: Bearer`           |
 * | claude    | `api.anthropic.com/v1/models`     | `x-api-key` + `anthropic-version` |
 * | gemini    | `.../v1beta/models?key=`          | key in query string               |
 * | localllm  | `{baseUrl}/v1/models`             | none                              |
 *
 * The fallback lists are intentional stale copies. They exist only for the offline case; the live
 * list wins whenever the provider answers.
 */
internal object ModelSources {

    /** Provider ids, in the order the settings screen shows them. */
    val PROVIDER_IDS = listOf("gemini", "openai", "claude", "deepseek", "grok", "localllm")

    private val ALL: Map<String, ModelSource> = listOf(
        OpenAiSource,
        DeepSeekSource,
        GrokSource,
        ClaudeSource,
        GeminiSource,
        LocalLlmSource
    ).associateBy { it.providerId }

    operator fun get(providerId: String): ModelSource? = ALL[providerId]

    /**
     * Fetches a Local LLM server's catalogue from an explicit endpoint.
     *
     * The settings dialog passes the URL the user just typed while the provider passes the stored
     * one; routing both through here keeps the parsing and filtering identical.
     *
     * [onError] is forwarded to the HTTP layer so interactive callers can show why it failed.
     */
    suspend fun fetchLocalModels(
        baseUrl: String,
        apiKey: String?,
        onError: ((String) -> Unit)? = null
    ): List<String> = withContext(Dispatchers.IO) {
        val endpoint = baseUrl.trim().trimEnd('/')
        if (endpoint.isEmpty()) return@withContext emptyList()

        val json = ModelCatalogHttp.getJson("$endpoint/v1/models", apiKey, onError = onError)
            ?: return@withContext emptyList()
        ModelCatalogHttp.parseOpenAiStyleIds(json)
            .filter { ModelCatalogFilters.isChatCapable(it) }
    }

    // ---------------------------------------------------------------- OpenAI

    private object OpenAiSource : ModelSource {
        override val providerId = "openai"

        override val fallbackModels = listOf(
            "gpt-5.1-codex-max",
            "gpt-5.1-codex",
            "gpt-5.1-codex-mini",
            "gpt-5-codex",
            "gpt-5.1",
            "gpt-5.1-chat-latest",
            "gpt-5",
            "gpt-5-mini",
            "gpt-5-nano",
            "gpt-5-pro",
            "gpt-4.1",
            "gpt-4.1-mini",
            "gpt-4.1-nano",
            "gpt-4o",
            "gpt-4o-mini",
            "o3",
            "o3-mini",
            "o4-mini"
        )

        override val defaultModel = "gpt-5.1-codex-max"

        override suspend fun fetchModels(apiKey: String?): List<String> = withContext(Dispatchers.IO) {
            val json = ModelCatalogHttp.getJson("https://api.openai.com/v1/models", apiKey)
                ?: return@withContext emptyList()
            // The raw catalogue holds hundreds of entries that cannot be called through
            // /chat/completions (embeddings, TTS, images, legacy completions-only models), so the
            // allow-list matters more here than anywhere else.
            ModelCatalogHttp.parseOpenAiStyleIds(json)
                .filter { ModelCatalogFilters.isOpenAiChatModel(it) }
        }
    }

    // -------------------------------------------------------------- DeepSeek

    private object DeepSeekSource : ModelSource {
        override val providerId = "deepseek"

        // "deepseek-chat" / "deepseek-reasoner" were retired; the API docs now name these two.
        override val fallbackModels = listOf(
            "deepseek-flash",
            "deepseek-v4-pro"
        )

        override val defaultModel = "deepseek-flash"

        override suspend fun fetchModels(apiKey: String?): List<String> = withContext(Dispatchers.IO) {
            // DeepSeek's base URL carries no /v1 suffix (the chat path is /chat/completions).
            val json = ModelCatalogHttp.getJson("https://api.deepseek.com/models", apiKey)
                ?: return@withContext emptyList()
            ModelCatalogHttp.parseOpenAiStyleIds(json)
                .filter { ModelCatalogFilters.isChatCapable(it) }
        }
    }

    // ------------------------------------------------------------------ Grok

    private object GrokSource : ModelSource {
        override val providerId = "grok"

        override val fallbackModels = listOf(
            "grok-4.6",
            "grok-4-1-fast-reasoning",
            "grok-4-1-fast-non-reasoning",
            "grok-code-fast-1",
            "grok-4-fast-reasoning",
            "grok-4-fast-non-reasoning",
            "grok-4-0709",
            "grok-3",
            "grok-3-mini"
        )

        override val defaultModel = "grok-4.6"

        override suspend fun fetchModels(apiKey: String?): List<String> = withContext(Dispatchers.IO) {
            val json = ModelCatalogHttp.getJson("https://api.x.ai/v1/models", apiKey)
                ?: return@withContext emptyList()
            ModelCatalogHttp.parseOpenAiStyleIds(json)
                .filter { ModelCatalogFilters.isChatCapable(it) }
        }
    }

    // ------------------------------------------------------------- Anthropic

    private object ClaudeSource : ModelSource {
        override val providerId = "claude"

        override val fallbackModels = listOf(
            "claude-sonnet-4-5-20250929",
            "claude-haiku-4-5-20251001",
            "claude-opus-4-5-20251101",
            "claude-opus-4-1-20250805",
            "claude-opus-4-20250514",
            "claude-sonnet-4-20250514",
            "claude-3-7-sonnet-20250219",
            "claude-3-5-haiku-20241022",
            "claude-3-haiku-20240307"
        )

        override val defaultModel = "claude-sonnet-4-5-20250929"

        override suspend fun fetchModels(apiKey: String?): List<String> = withContext(Dispatchers.IO) {
            // Anthropic does not accept a bearer token here: the key travels in a bare `x-api-key`
            // header and the API version must be pinned explicitly.
            val json = ModelCatalogHttp.getJson(
                url = "https://api.anthropic.com/v1/models",
                apiKey = apiKey,
                apiKeyHeader = "x-api-key",
                apiKeyPrefix = "",
                headers = mapOf("anthropic-version" to "2023-06-01")
            ) ?: return@withContext emptyList()
            ModelCatalogHttp.parseOpenAiStyleIds(json)
                .filter { ModelCatalogFilters.isChatCapable(it) }
        }
    }

    // ---------------------------------------------------------------- Gemini

    private object GeminiSource : ModelSource {
        override val providerId = "gemini"

        override val fallbackModels = listOf(
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite"
        )

        // Deliberately not the newest entry in the catalogue: preview models must not be pushed to
        // users as the default, and this matches what the provider defaulted to before.
        override val defaultModel = "gemini-2.5-pro"

        override suspend fun fetchModels(apiKey: String?): List<String> = withContext(Dispatchers.IO) {
            if (apiKey.isNullOrBlank()) return@withContext emptyList()

            // Gemini authenticates via the query string and returns `models[]` entries whose
            // `name` is prefixed with "models/", which the chat API does not expect.
            val json = ModelCatalogHttp.getJson(
                "https://generativelanguage.googleapis.com/v1beta/models" +
                    "?key=${ModelCatalogHttp.encode(apiKey)}&pageSize=200"
            ) ?: return@withContext emptyList()

            val models = json.optJSONArray("models") ?: return@withContext emptyList()
            (0 until models.length()).mapNotNull { index ->
                val entry = models.optJSONObject(index) ?: return@mapNotNull null

                // Without this check the list fills up with embedContent-only models that fail on
                // every generate request.
                val methods = entry.optJSONArray("supportedGenerationMethods")
                val canGenerate = methods != null && (0 until methods.length())
                    .any { methods.optString(it) == "generateContent" }
                if (!canGenerate) return@mapNotNull null

                entry.optString("name")
                    .removePrefix("models/")
                    .takeIf { it.isNotBlank() && ModelCatalogFilters.isChatCapable(it) }
            }
        }
    }

    // ------------------------------------------------------------- Local LLM

    private object LocalLlmSource : ModelSource {
        override val providerId = LocalLlmSettings.PROVIDER_ID

        // Whatever the server actually serves is discovered at runtime; there is no meaningful
        // offline answer, but the list must stay non-empty for the picker.
        override val fallbackModels = listOf(LocalLlmSettings.DEFAULT_MODEL)

        override val defaultModel = LocalLlmSettings.DEFAULT_MODEL

        override suspend fun fetchModels(apiKey: String?): List<String> = withContext(Dispatchers.IO) {
            // Same source the provider itself reads, so no Context needs to be threaded through.
            val baseUrl = LocalLlmSettings.baseUrl() ?: return@withContext emptyList()

            // Shares the parsing and filtering with the settings dialog, which fetches from the
            // URL the user is editing rather than from the stored one.
            ModelSources.fetchLocalModels(baseUrl, apiKey)
        }
    }
}
