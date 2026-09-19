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

class ReadFilePartTool(private val isPathAllowed: (String) -> Boolean) : AgentTool {

    override val spec = AgentToolSpec(
        name = NAME,
        description = "Read a line range of a file, with line numbers. Lines are 1-indexed and the range is inclusive. Defaults to the first ${ToolLimits.DEFAULT_FILE_READ_PART_LINES} lines.",
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
                    put(
                        "start_line",
                        JSONObject().apply {
                            put("type", "integer")
                            put("description", "First line to read, 1-indexed. Defaults to 1.")
                        }
                    )
                    put(
                        "end_line",
                        JSONObject().apply {
                            put("type", "integer")
                            put(
                                "description",
                                "Last line to read, 1-indexed and inclusive. Defaults to start_line + ${ToolLimits.DEFAULT_FILE_READ_PART_LINES - 1}."
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
        if (!file.exists()) {
            return AgentToolResult("File not found: $path", isError = true)
        }
        if (!file.isFile) {
            return AgentToolResult(
                "Not a file (use list_files for directories): $path",
                isError = true
            )
        }

        val start = json.optInt("start_line", 1).coerceAtLeast(1)
        val end = if (json.has("end_line")) {
            json.optInt("end_line")
        } else {
            start + ToolLimits.DEFAULT_FILE_READ_PART_LINES - 1
        }
        if (end < start) {
            return AgentToolResult("end_line ($end) is before start_line ($start).", isError = true)
        }

        return try {
            val window = file.useLines { lines ->
                lines.drop(start - 1).take(end - start + 1).toList()
            }
            if (window.isEmpty()) {
                return AgentToolResult(
                    "start_line $start is beyond the end of the file: $path",
                    isError = true
                )
            }

            val joined = window.joinToString("\n")
            val limit = ToolLimits.MAX_FILE_READ_CHARS
            val clipped = if (joined.length <= limit) joined else joined.take(limit)
            var body = addLineNumbers(clipped, start)
            if (clipped.length < joined.length) {
                body += "\n\n[Truncated at $limit characters; narrow the range.]"
            }
            if (window.size < end - start + 1) {
                body += "\n\n[end of file reached]"
            }
            AgentToolResult(body)
        } catch (e: Exception) {
            AgentToolResult("Failed to read file: ${e.message}", isError = true)
        }
    }

    override fun summarize(arguments: String): String {
        val json = parseToolArguments(arguments) ?: return ""
        val path = json.optString("path")
        if (!json.has("start_line") && !json.has("end_line")) return path
        val start = json.optInt("start_line", 1).coerceAtLeast(1)
        val end = if (json.has("end_line")) {
            json.optInt("end_line")
        } else {
            start + ToolLimits.DEFAULT_FILE_READ_PART_LINES - 1
        }
        return "$path:$start-$end"
    }

    private companion object {
        const val NAME = "read_file_part"
    }
}