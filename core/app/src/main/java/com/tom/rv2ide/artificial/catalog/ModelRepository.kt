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

import android.content.Context
import android.content.SharedPreferences
import com.tom.rv2ide.artificial.secrets.ApiKey
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Reads and refreshes provider model catalogues.
 *
 * Reading is deliberately synchronous and never touches the network: [getModels] is called from the
 * request path (to resolve the stored model) and from the settings screen, and neither may block on
 * a provider round-trip. Fetching is a separate, explicit, suspending step ([refresh]) that the
 * settings screen triggers when the user asks for it.
 *
 * Degradation order, first hit wins:
 *  1. in-memory cache (this process)
 *  2. on-disk cache, if younger than [CACHE_TTL_MS]
 *  3. [ModelSource.fallbackModels]
 *
 * Any of these is always non-empty, so callers never have to handle an empty list.
 */
object ModelRepository {

    /** Catalogues change slowly; a day keeps the picker fresh without hammering the providers. */
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    private const val PREFS_NAME = "ai_model_catalog"
    private const val KEY_MODELS_PREFIX = "models_"
    private const val KEY_TIMESTAMP_PREFIX = "models_ts_"

    private val memoryCache = ConcurrentHashMap<String, List<String>>()

    private val prefs: SharedPreferences? by lazy {
        try {
            com.tom.rv2ide.app.BaseApplication.getBaseInstance()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Models to offer for [providerId]. Never empty: falls back to the bundled list when nothing
     * has been fetched yet.
     */
    fun getModels(providerId: String): List<String> {
        val source = ModelSources[providerId] ?: return emptyList()

        memoryCache[providerId]?.takeIf { it.isNotEmpty() }?.let { return it }

        readDiskCache(providerId)?.let { cached ->
            memoryCache[providerId] = cached
            return cached
        }

        return source.fallbackModels
    }

    /**
     * The model to use when the stored one is missing or no longer offered.
     *
     * Single source of truth for provider defaults; see [ModelSource.defaultModel].
     */
    fun getDefaultModel(providerId: String): String =
        ModelSources[providerId]?.defaultModel ?: ""

    /**
     * Fetches the live catalogue and caches it.
     *
     * Returns a failure instead of throwing so the caller (settings screen) can surface a message.
     * A failed refresh leaves the previous cache intact — a transient outage must not wipe a good
     * list.
     */
    suspend fun refresh(providerId: String): RefreshResult {
        val source = ModelSources[providerId]
            ?: return RefreshResult.Failure("Unknown provider: $providerId")

        val models = try {
            source.fetchModels(apiKeyFor(providerId))
        } catch (e: Exception) {
            // Sources are written to swallow errors, but a bug in one of them must not break the
            // whole refresh path.
            return RefreshResult.Failure(e.message ?: "Failed to fetch models")
        }

        if (models.isEmpty()) {
            return RefreshResult.Failure(
                "No models reported by the provider. Keeping the built-in list."
            )
        }

        memoryCache[providerId] = models
        writeDiskCache(providerId, models)
        return RefreshResult.Success(models)
    }

    /** When the cached list was fetched, or `null` when only the built-in list is available. */
    fun lastUpdated(providerId: String): Long? {
        val timestamp = prefs?.getLong(KEY_TIMESTAMP_PREFIX + providerId, 0L) ?: 0L
        return timestamp.takeIf { it > 0 }
    }

    /**
     * Fetches a Local LLM server's catalogue from the URL the user typed, without caching it.
     *
     * Caching is a separate, explicit step ([cacheLocalModels]) because the dialog can be cancelled:
     * storing a list fetched from an endpoint that was never saved would leave the picker showing
     * one server's models while the provider talks to another.
     *
     * Unlike [refresh] this reports *why* it failed, since the user is waiting on a button.
     */
    suspend fun fetchLocalModels(baseUrl: String, apiKey: String?): LocalModelsResult {
        val endpoint = baseUrl.trim()
        if (endpoint.isEmpty()) return LocalModelsResult.Failure("Base URL is empty")

        var failure: String? = null
        val models = ModelSources.fetchLocalModels(endpoint, apiKey) { failure = it }

        return if (models.isEmpty()) {
            LocalModelsResult.Failure(failure ?: "No models reported by the server")
        } else {
            LocalModelsResult.Success(models)
        }
    }

    /**
     * Publishes a fetched Local LLM list to the rest of the app.
     *
     * Called once the corresponding endpoint has actually been saved, so the cached list and the
     * configured base URL always describe the same server.
     */
    fun cacheLocalModels(models: List<String>) {
        val cleaned = models.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return

        memoryCache[LocalLlmSettings.PROVIDER_ID] = cleaned
        writeDiskCache(LocalLlmSettings.PROVIDER_ID, cleaned)
    }

    private fun readDiskCache(providerId: String): List<String>? {
        val store = prefs ?: return null
        val timestamp = store.getLong(KEY_TIMESTAMP_PREFIX + providerId, 0L)
        if (timestamp <= 0 || System.currentTimeMillis() - timestamp > CACHE_TTL_MS) return null

        val raw = store.getString(KEY_MODELS_PREFIX + providerId, null) ?: return null
        return try {
            val array = JSONArray(raw)
            (0 until array.length())
                .mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeDiskCache(providerId: String, models: List<String>) {
        val store = prefs ?: return
        val array = JSONArray()
        models.forEach { array.put(it) }
        store.edit()
            .putString(KEY_MODELS_PREFIX + providerId, array.toString())
            .putLong(KEY_TIMESTAMP_PREFIX + providerId, System.currentTimeMillis())
            .apply()
    }

    private fun apiKeyFor(providerId: String): String? = when (providerId) {
        "gemini" -> ApiKey.getGeminiApiKey()
        "openai" -> ApiKey.getOpenAIApiKey()
        "claude" -> ApiKey.getAnthropicApiKey()
        "deepseek" -> ApiKey.getDeepseekApiKey()
        "grok" -> ApiKey.getGrokApiKey()
        // Local servers are usually unauthenticated, but the settings dialog offers a key for the
        // ones that are not, and it has to be honoured here or that field would do nothing.
        LocalLlmSettings.PROVIDER_ID -> LocalLlmSettings.apiKey()
        else -> null
    }.takeIf { !it.isNullOrBlank() }

    sealed interface RefreshResult {
        data class Success(val models: List<String>) : RefreshResult
        data class Failure(val reason: String) : RefreshResult
    }

    /** Outcome of an interactive [fetchLocalModels] call, with a reason the UI can display. */
    sealed interface LocalModelsResult {
        data class Success(val models: List<String>) : LocalModelsResult
        data class Failure(val reason: String) : LocalModelsResult
    }
}