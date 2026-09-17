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
import org.json.JSONObject

class ListFilesTool(
    private val isPathAllowed: (String) -> Boolean,
    private val projectRoot: File?
) : AgentTool {

    override val spec = AgentToolSpec(
        name = NAME,
        description = "List the entries of a directory (directories end with /). Defaults to the project root.",
        parameters = JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    put(
                        "path",
                        JSONObject().apply {
                            put("type", "string")
                            put(
                                "description",
                                "Absolute path of the directory. Defaults to the project root."
                            )
                        }
                    )
                }
            )
        }
    )

    override suspend fun execute(arguments: String): AgentToolResult {
        val json = parseToolArguments(arguments) ?: JSONObject()
        val requested = json.optString("path")
        val path = if (requested.isBlank()) {
            projectRoot?.absolutePath
                ?: return AgentToolResult("Project root is not set", isError = true)
        } else {
            requested
        }
        if (!isPathAllowed(path)) {
            return AgentToolResult("Path is outside the project: $path", isError = true)
        }

        val directory = File(path)
        if (!directory.exists()) {
            return AgentToolResult("Directory not found: $path", isError = true)
        }
        if (!directory.isDirectory) {
            return AgentToolResult(
                "Not a directory (use read_file for files): $path",
                isError = true
            )
        }

        val entries = directory.listFiles()
            ?: return AgentToolResult("Failed to list directory: $path", isError = true)
        val sorted = entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        val lines = sorted.take(MAX_ENTRIES).map { entry ->
            if (entry.isDirectory) entry.name + "/" else entry.name
        }
        val body = if (lines.isEmpty()) "(empty directory)" else lines.joinToString("\n")
        return AgentToolResult(
            if (sorted.size > MAX_ENTRIES) {
                body + "\n[Truncated: showing $MAX_ENTRIES of ${sorted.size} entries]"
            } else {
                body
            }
        )
    }

    override fun summarize(arguments: String): String =
        parseToolArguments(arguments)?.optString("path")?.takeIf { it.isNotBlank() }
            ?: projectRoot?.absolutePath.orEmpty()

    private companion object {
        const val NAME = "list_files"
        const val MAX_ENTRIES = 200
    }
}
