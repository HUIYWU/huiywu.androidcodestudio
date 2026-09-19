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
import com.tom.rv2ide.artificial.file.FileWriteResult
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class CreateFileTool(
    private val isPathAllowed: (String) -> Boolean,
    private val writeFile: suspend (filePath: String, content: String, append: Boolean) -> FileWriteResult
) : AgentTool {

    override val spec = AgentToolSpec(
        name = NAME,
        description = "Create a new file with the given content. Fails when the file already exists: use edit_file to change an existing file.",
        parameters = JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    put(
                        "path",
                        JSONObject().apply {
                            put("type", "string")
                            put("description", "Absolute path of the file to create.")
                        }
                    )
                    put(
                        "new",
                        JSONObject().apply {
                            put("type", "string")
                            put("description", "The content of the new file.")
                        }
                    )
                }
            )
            put("required", JSONArray().put("path").put("new"))
        }
    )

    override suspend fun execute(arguments: String): AgentToolResult {
        val json = parseToolArguments(arguments)
            ?: return AgentToolResult("Invalid arguments: ${arguments.take(200)}", isError = true)
        val path = json.optString("path")
        if (path.isBlank()) {
            return AgentToolResult("Missing required parameter: path", isError = true)
        }
        if (!json.has("new")) {
            return AgentToolResult("Missing required parameter: new", isError = true)
        }
        if (!isPathAllowed(path)) {
            return AgentToolResult("Path is outside the project: $path", isError = true)
        }

        val file = File(path)
        if (file.exists()) {
            return AgentToolResult(
                "File already exists: $path. Use edit_file to change it, or delete_file first.",
                isError = true
            )
        }

        return when (val result = writeFile(path, json.optString("new"), false)) {
            is FileWriteResult.Success -> AgentToolResult("File created: $path")
            is FileWriteResult.PermissionDenied ->
                AgentToolResult("Create failed: ${result.reason}", isError = true)
            is FileWriteResult.Error ->
                AgentToolResult("Create failed: ${result.message}", isError = true)
        }
    }

    override fun summarize(arguments: String): String =
        parseToolArguments(arguments)?.optString("path").orEmpty()

    companion object {
        const val NAME = "create_file"
    }
}