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
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class ReadFileTool(private val isPathAllowed: (String) -> Boolean) : AgentTool {

    override val spec = AgentToolSpec(
        name = NAME,
        description = "Read the complete content of a file. Read a file before you modify it.",
        parameters = JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    put(
                        "path",
                        JSONObject().apply {
                            put("type", "string")
                            put("description", "Absolute path of the file to read.")
                        }
                    )
                }
            )
            put("required", JSONArray().put("path"))
        }
    )

    override suspend fun execute(arguments: String): AgentToolResult {
        val json = parseToolArguments(arguments)
            ?: return AgentToolResult("Invalid arguments: ${arguments.take(200)}", isError = true)
        val path = json.optString("path")
        if (path.isBlank()) {
            return AgentToolResult("Missing required parameter: path", isError = true)
        }
        if (!isPathAllowed(path)) {
            return AgentToolResult("Path is outside the project: $path", isError = true)
        }

        val file = File(path)
        if (!file.exists()) {
            return AgentToolResult("File not found: $path", isError = true)
        }
        if (!file.isFile) {
            return AgentToolResult(
                "Not a file (use list_files for directories): $path",
                isError = true
            )
        }

        return try {
            val text = file.readText()
            if (text.length <= MAX_CHARS) {
                AgentToolResult(text)
            } else {
                AgentToolResult(
                    text.take(MAX_CHARS) + "\n\n[Truncated: file is ${text.length} characters]"
                )
            }
        } catch (e: Exception) {
            AgentToolResult("Failed to read file: ${e.message}", isError = true)
        }
    }

    override fun summarize(arguments: String): String =
        parseToolArguments(arguments)?.optString("path").orEmpty()

    private companion object {
        const val NAME = "read_file"
        const val MAX_CHARS = 60_000
    }
}
