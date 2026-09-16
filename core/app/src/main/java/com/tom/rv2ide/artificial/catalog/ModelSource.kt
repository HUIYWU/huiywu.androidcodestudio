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

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Where a single provider's model list comes from.
 *
 * Providers publish their catalogue remotely and change it without warning, so a list compiled
 * into the APK can never be the primary source of truth. [fetchModels] is the live source;
 * [fallbackModels] only exists so the app still works offline or when the provider refuses to
 * answer. Callers must never assume [fallbackModels] is current.
 */
interface ModelSource {

    val providerId: String

    /**
     * Name shown to the user for this provider.
     *
     * Lives here for the same reason [defaultModel] does: the settings screen, the error dialogs and
     * the provider itself all need it, and a literal per call site is how the six names drifted apart.
     */
    val providerName: String

    /** Offline / degraded-mode list. Must never be empty, otherwise the selection UI breaks. */
    val fallbackModels: List<String>

    /**
     * Model used when nothing usable is stored yet.
     *
     * This is the single source of truth for "the default model of this provider": providers must
     * not keep a private literal copy, because those copies silently diverged from the list and
     * ended up pointing at retired models.
     */
    val defaultModel: String

    /**
     * Fetches the live catalogue for this provider.
     *
     * Returns an empty list instead of throwing on *any* failure (offline, revoked key, provider
     * outage, malformed body). The repository reads an empty result as "unavailable" and degrades
     * to [fallbackModels]; a thrown exception would instead break the caller's refresh flow.
     *
     * [onError] carries the reason an empty result is empty. It is only consumed by interactive
     * callers, which have to tell the user whether the key was rejected or the endpoint was wrong.
     */
    suspend fun fetchModels(apiKey: String?, onError: ((String) -> Unit)? = null): List<String>
}

/**
 * Shared HTTP plumbing for catalogue endpoints.
 *
 * Only platform APIs are used on purpose: the request providers already use [HttpURLConnection],
 * and OkHttp is merely a transitive dependency here, so it must not become something new code
 * relies on.
 */
internal object ModelCatalogHttp {

    private const val TIMEOUT_MS = 10_000

    /**
     * Performs a GET and parses the body as JSON, returning `null` on any failure.
     *
     * The providers disagree on authentication, which is why the header name and value prefix are
     * parameters: OpenAI/DeepSeek/Grok/Local LLM use `Authorization: Bearer <key>`, Anthropic uses
     * a bare `x-api-key` plus a version header, and Gemini puts the key in the query string.
     *
     * [onError] exists for the interactive callers only: a background refresh degrades silently by
     * design, but a user waiting for a button must be told *why* nothing came back ("HTTP 404" is
     * the difference between a wrong endpoint and a stopped server).
     */
    fun getJson(
        url: String,
        apiKey: String? = null,
        apiKeyHeader: String = "Authorization",
        apiKeyPrefix: String = "Bearer ",
        headers: Map<String, String> = emptyMap(),
        onError: ((String) -> Unit)? = null
    ): JSONObject? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                if (!apiKey.isNullOrBlank()) {
                    connection.setRequestProperty(apiKeyHeader, "$apiKeyPrefix$apiKey")
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    JSONObject(connection.inputStream.bufferedReader().readText())
                } else {
                    onError?.invoke("HTTP ${connection.responseCode}")
                    null
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            onError?.invoke(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    /**
     * Extracts `data[].id`, the shape shared by every OpenAI-compatible catalogue endpoint
     * (OpenAI, DeepSeek, Grok, Local LLM) and by Anthropic's Models API.
     */
    fun parseOpenAiStyleIds(json: JSONObject): List<String> {
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            data.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }
        }
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/**
 * Keeps non-chat models out of the picker.
 *
 * A provider's catalogue is a superset of what this app can call: it contains embeddings, speech,
 * image and moderation endpoints, and calling those through `/chat/completions` always fails. The
 * hard-coded list used to carry such entries by hand; a dynamically fetched list would bring in
 * hundreds of them, so they have to be filtered out.
 */
internal object ModelCatalogFilters {

    private val NON_CHAT_KEYWORDS = listOf(
        "embedding", "tts", "whisper", "dall-e", "moderation",
        "audio", "realtime", "image", "babbage", "davinci"
    )

    /** Chat-capable families; everything else in OpenAI's catalogue is not callable here. */
    private val OPENAI_CHAT_PREFIXES = listOf("gpt-", "o1", "o3", "o4")

    /** Drops models that are obviously not usable through a chat-completions endpoint. */
    fun isChatCapable(id: String): Boolean {
        val lower = id.lowercase()
        return NON_CHAT_KEYWORDS.none { lower.contains(it) }
    }

    /**
     * OpenAI's catalogue additionally needs an allow-list: it exposes many non-`gpt` text models
     * (and legacy completions-only ones) that pass [isChatCapable] but reject chat requests.
     */
    fun isOpenAiChatModel(id: String): Boolean {
        val lower = id.lowercase()
        return isChatCapable(lower) && OPENAI_CHAT_PREFIXES.any { lower.startsWith(it) }
    }
}
