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

/**
 * Display names of the providers.
 *
 * A single source because the name a provider is shown under is not derived from its id (`claude` is
 * shown as "Anthropic Claude", `localllm` as "Local LLM"), and the mapping was previously copied into
 * every screen that listed providers — which is how one of the copies ended up missing two entries.
 *
 * The ids themselves come from [ModelSources.PROVIDER_IDS]; this only names them.
 */
internal object ProviderLabels {

    private val LABELS = mapOf(
        "gemini" to "Google Gemini",
        "openai" to "OpenAI",
        "claude" to "Anthropic Claude",
        "deepseek" to "DeepSeek",
        "grok" to "xAI Grok",
        LocalLlmSettings.PROVIDER_ID to "Local LLM"
    )

    /**
     * The display name of [providerId], or the id itself when it has none.
     *
     * Falling back to the id rather than throwing keeps a newly registered provider usable before it
     * is named here, which is the failure mode the per-screen copies had to guard against anyway.
     */
    fun of(providerId: String): String = LABELS[providerId] ?: providerId
}
