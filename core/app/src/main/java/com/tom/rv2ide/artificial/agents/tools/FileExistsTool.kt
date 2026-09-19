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

package com.tom.rv2ide.artificial.agents.tools

import com.tom.rv2ide.artificial.agents.AgentToolSpec
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class FileExistsTool(private val isPathAllowed: (String) -> Boolean) : AgentTool {

    override val spec = AgentToolSpec(
        name = NAME,
        description = "Check whether a path exists, and whether it is a file or a directory.",
        parameters = JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    put(
                        "path",
                        JSONObject().apply {
                            put("type", "string")
                            put("description", "Absolute path to check.")
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
        return when {
            !file.exists() -> AgentToolResult("exists: false")
            file.isDirectory -> AgentToolResult("exists: true (directory)")
            else -> AgentToolResult("exists: true (file)")
        }
    }

    override fun summarize(arguments: String): String =
        parseToolArguments(arguments)?.optString("path").orEmpty()

    private companion object {
        const val NAME = "file_exists"
    }
}