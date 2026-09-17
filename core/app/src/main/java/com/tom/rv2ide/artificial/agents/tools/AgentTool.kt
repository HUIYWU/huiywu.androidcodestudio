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

package com.tom.rv2ide.artificial.agents.tools

import com.tom.rv2ide.artificial.agents.AgentToolSpec
import org.json.JSONException
import org.json.JSONObject

/**
 * One capability offered to the model.
 *
 * [execute] never throws for a bad request: an unusable call becomes an error result, which the
 * request loop sends back as feedback the model can act on.
 */
interface AgentTool {
    val spec: AgentToolSpec

    suspend fun execute(arguments: String): AgentToolResult

    /** Short, human-readable form of the arguments for the transcript row. */
    fun summarize(arguments: String): String
}

data class AgentToolResult(
    val content: String,
    val isError: Boolean = false
)

internal fun parseToolArguments(raw: String): JSONObject? =
    try {
        JSONObject(raw)
    } catch (e: JSONException) {
        null
    }
