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

class DeleteFileTool(
    private val isPathAllowed: (String) -> Boolean,
    private val canonicalAllowedRoots: Set<String>
) : AgentTool {

    override val spec = AgentToolSpec(
        name = NAME,
        description = "Delete a file or directory. A non-empty directory requires recursive=true.",
        parameters = JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    put(
                        "path",
                        JSONObject().apply {
                            put("type", "string")
                            put("description", "Absolute path of the file or directory to delete.")
                        }
                    )
                    put(
                        "recursive",
                        JSONObject().apply {
                            put("type", "boolean")
                            put(
                                "description",
                                "Required to be true to delete a non-empty directory. Defaults to false."
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

        val file = File(path)
        val canonical = try {
            file.canonicalPath
        } catch (e: Exception) {
            file.absolutePath
        }
        if (canonicalAllowedRoots.contains(canonical)) {
            return AgentToolResult("Refusing to delete a project root directory: $path", isError = true)
        }
        if (!file.exists()) {
            return AgentToolResult("File not found: $path", isError = true)
        }

        val recursive = json.optBoolean("recursive", false)
        if (file.isDirectory && !recursive) {
            val children = file.listFiles()
            if (children?.isNotEmpty() == true) {
                return AgentToolResult(
                    "Directory is not empty: $path. Pass recursive=true to delete it and its contents.",
                    isError = true
                )
            }
        }

        val deleted = if (recursive) file.deleteRecursively() else file.delete()
        return if (deleted) {
            AgentToolResult("Deleted: $path")
        } else {
            AgentToolResult("Failed to delete: $path", isError = true)
        }
    }

    override fun summarize(arguments: String): String =
        parseToolArguments(arguments)?.optString("path").orEmpty()

    private companion object {
        const val NAME = "delete_file"
    }
}