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

class MakeDirectoryTool(private val isPathAllowed: (String) -> Boolean) : AgentTool {

    override val spec = AgentToolSpec(
        name = NAME,
        description = "Create a directory. Pass create_parents=true to also create missing parent directories.",
        parameters = JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    put(
                        "path",
                        JSONObject().apply {
                            put("type", "string")
                            put("description", "Absolute path of the directory to create.")
                        }
                    )
                    put(
                        "create_parents",
                        JSONObject().apply {
                            put("type", "boolean")
                            put(
                                "description",
                                "When true, missing parent directories are created as well. Defaults to false."
                            )
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

        val directory = File(path)
        if (directory.exists()) {
            return if (directory.isDirectory) {
                AgentToolResult("Directory already exists: $path")
            } else {
                AgentToolResult("A file with that name exists: $path", isError = true)
            }
        }

        val createParents = json.optBoolean("create_parents", false)
        val created = if (createParents) directory.mkdirs() else directory.mkdir()
        return if (created) {
            AgentToolResult("Directory created: $path")
        } else {
            AgentToolResult(
                "Failed to create directory: $path (pass create_parents=true if its parent is missing)",
                isError = true
            )
        }
    }

    override fun summarize(arguments: String): String =
        parseToolArguments(arguments)?.optString("path").orEmpty()

    private companion object {
        const val NAME = "make_directory"
    }
}