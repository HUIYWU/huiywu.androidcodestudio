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
import com.tom.rv2ide.artificial.file.FileWriteResult
import org.json.JSONArray
import org.json.JSONObject

class WriteFileTool(
    private val isPathAllowed: (String) -> Boolean,
    private val writeFile: suspend (filePath: String, content: String) -> FileWriteResult
) : AgentTool {

    override val spec = AgentToolSpec(
        name = NAME,
        description = "Write the complete final content of a file. Missing parent directories are created; an existing file is replaced.",
        parameters = JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    put(
                        "path",
                        JSONObject().apply {
                            put("type", "string")
                            put("description", "Absolute path of the file to write.")
                        }
                    )
                    put(
                        "content",
                        JSONObject().apply {
                            put("type", "string")
                            put("description", "The complete content the file must end up with.")
                        }
                    )
                }
            )
            put("required", JSONArray().put("path").put("content"))
        }
    )

    override suspend fun execute(arguments: String): AgentToolResult {
        val json = parseToolArguments(arguments)
            ?: return AgentToolResult("Invalid arguments: ${arguments.take(200)}", isError = true)
        val path = json.optString("path")
        if (path.isBlank()) {
            return AgentToolResult("Missing required parameter: path", isError = true)
        }
        if (!json.has("content")) {
            return AgentToolResult("Missing required parameter: content", isError = true)
        }
        if (!isPathAllowed(path)) {
            return AgentToolResult("Path is outside the project: $path", isError = true)
        }

        return when (val result = writeFile(path, json.optString("content"))) {
            is FileWriteResult.Success -> AgentToolResult("File written: $path")
            is FileWriteResult.PermissionDenied ->
                AgentToolResult("Write failed: ${result.reason}", isError = true)
            is FileWriteResult.Error ->
                AgentToolResult("Write failed: ${result.message}", isError = true)
        }
    }

    override fun summarize(arguments: String): String =
        parseToolArguments(arguments)?.optString("path").orEmpty()

    companion object {
        /**
         * Matched against by the request loop, which renders this tool's calls as file rows instead
         * of tool rows.
         */
        const val NAME = "write_file"
    }
}
