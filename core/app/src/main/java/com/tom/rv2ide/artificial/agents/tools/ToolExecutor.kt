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

import com.tom.rv2ide.artificial.agents.AgentToolCall
import com.tom.rv2ide.artificial.agents.AgentToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the tools the model asks for.
 *
 * Execution happens on the IO dispatcher: tools touch the disk, while the request that drives them
 * runs on the main dispatcher. A failing tool is reported as an error result, never as an exception,
 * so a bad call becomes feedback the model can act on.
 */
class ToolExecutor(private val tools: List<AgentTool>) {

    val specs: List<AgentToolSpec> = tools.map { it.spec }

    suspend fun execute(call: AgentToolCall): AgentToolResult {
        val tool = tools.firstOrNull { it.spec.name == call.name }
            ?: return AgentToolResult("Unknown tool: ${call.name}", isError = true)
        return withContext(Dispatchers.IO) {
            try {
                tool.execute(call.arguments)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AgentToolResult("Tool failed: ${e.message}", isError = true)
            }
        }
    }

    fun summarize(call: AgentToolCall): String =
        tools.firstOrNull { it.spec.name == call.name }?.summarize(call.arguments)
            ?: call.arguments.take(80)
}