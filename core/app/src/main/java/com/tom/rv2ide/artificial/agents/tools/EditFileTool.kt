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

class EditFileTool(
    private val isPathAllowed: (String) -> Boolean,
    private val writeFile: suspend (filePath: String, content: String, append: Boolean) -> FileWriteResult
) : AgentTool {

    override val spec = AgentToolSpec(
        name = NAME,
        description = "Edit an existing file by replacing an exact fragment. 'old' must match the file content precisely (whitespace included; omit the line-number prefixes read_file shows). A fragment that matches several places is rejected: include enough surrounding context to make it unique. 'new' may be empty to delete the matched fragment.",
        parameters = JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    put(
                        "path",
                        JSONObject().apply {
                            put("type", "string")
                            put("description", "Absolute path of the file to edit.")
                        }
                    )
                    put(
                        "old",
                        JSONObject().apply {
                            put("type", "string")
                            put(
                                "description",
                                "The exact content to find and replace; must match a single place in the file."
                            )
                        }
                    )
                    put(
                        "new",
                        JSONObject().apply {
                            put("type", "string")
                            put("description", "The replacement content; an empty string deletes the fragment.")
                        }
                    )
                }
            )
            put("required", JSONArray().put("path").put("old").put("new"))
        }
    )

    override suspend fun execute(arguments: String): AgentToolResult {
        val json = parseToolArguments(arguments)
            ?: return AgentToolResult("Invalid arguments: ${arguments.take(200)}", isError = true)
        val path = json.optString("path")
        if (path.isBlank()) {
            return AgentToolResult("Missing required parameter: path", isError = true)
        }
        if (!json.has("old")) {
            return AgentToolResult("Missing required parameter: old", isError = true)
        }
        if (!json.has("new")) {
            return AgentToolResult("Missing required parameter: new", isError = true)
        }
        val old = json.optString("old")
        if (old.isEmpty()) {
            return AgentToolResult("Parameter 'old' must not be empty.", isError = true)
        }
        if (!isPathAllowed(path)) {
            return AgentToolResult("Path is outside the project: $path", isError = true)
        }

        val file = File(path)
        if (!file.exists()) {
            return AgentToolResult(
                "File not found: $path (use create_file to create it)",
                isError = true
            )
        }
        if (!file.isFile) {
            return AgentToolResult(
                "Not a file (use list_files for directories): $path",
                isError = true
            )
        }

        val new = json.optString("new")
        return try {
            val content = file.readText()

            var matchStart = content.indexOf(old)
            var matchText = old
            if (matchStart < 0) {
                val stripped = stripLineNumberPrefixes(old)
                if (stripped != old) {
                    matchStart = content.indexOf(stripped)
                    matchText = stripped
                }
            }
            if (matchStart < 0) {
                return AgentToolResult(
                    "'old' was not found in $path. It must match the file exactly; " +
                        "read the file again and copy the content precisely (including whitespace).",
                    isError = true
                )
            }

            val occurrences = countOccurrences(content, matchText)
            if (occurrences > 1) {
                return AgentToolResult(
                    "'old' matches $occurrences places in $path. " +
                        "Include more surrounding context so it matches a single location.",
                    isError = true
                )
            }

            val updated =
                content.substring(0, matchStart) + new + content.substring(matchStart + matchText.length)

            when (val result = writeFile(path, updated, false)) {
                is FileWriteResult.Success -> AgentToolResult("File edited: $path")
                is FileWriteResult.PermissionDenied ->
                    AgentToolResult("Edit failed: ${result.reason}", isError = true)
                is FileWriteResult.Error ->
                    AgentToolResult("Edit failed: ${result.message}", isError = true)
            }
        } catch (e: Exception) {
            AgentToolResult("Failed to edit file: ${e.message}", isError = true)
        }
    }

    override fun summarize(arguments: String): String =
        parseToolArguments(arguments)?.optString("path").orEmpty()

    private fun countOccurrences(content: String, fragment: String): Int {
        var count = 0
        var index = content.indexOf(fragment)
        while (index >= 0) {
            count++
            index = content.indexOf(fragment, index + fragment.length)
        }
        return count
    }

    companion object {
        const val NAME = "edit_file"
    }
}