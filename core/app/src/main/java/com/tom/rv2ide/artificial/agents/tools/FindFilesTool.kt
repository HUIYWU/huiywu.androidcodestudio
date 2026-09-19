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

class FindFilesTool(
    private val isPathAllowed: (String) -> Boolean,
    private val projectRoot: File?
) : AgentTool {

    override val spec = AgentToolSpec(
        name = NAME,
        description = "Find files by name pattern (for example *.kt) under a directory. Directories themselves are not returned.",
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
                                "Absolute path of the directory to search. Defaults to the project root."
                            )
                        }
                    )
                    put(
                        "pattern",
                        JSONObject().apply {
                            put("type", "string")
                            put("description", "File name pattern with * and ? wildcards, for example *.kt.")
                        }
                    )
                    put(
                        "max_depth",
                        JSONObject().apply {
                            put("type", "integer")
                            put(
                                "description",
                                "How many directory levels to search; -1 or omitted means unlimited."
                            )
                        }
                    )
                    put(
                        "case_insensitive",
                        JSONObject().apply {
                            put("type", "boolean")
                            put("description", "Match the pattern case-insensitively. Defaults to false.")
                        }
                    )
                }
            )
            put("required", JSONArray().put("pattern"))
        }
    )

    override suspend fun execute(arguments: String): AgentToolResult {
        val json = parseToolArguments(arguments)
            ?: return AgentToolResult("Invalid arguments: ${arguments.take(200)}", isError = true)
        val pattern = json.optString("pattern")
        if (pattern.isBlank()) {
            return AgentToolResult("Missing required parameter: pattern", isError = true)
        }
        val rootPath = json.optString("path").takeIf { it.isNotBlank() }
            ?: projectRoot?.absolutePath
            ?: return AgentToolResult("Project root is not set", isError = true)
        if (!isPathAllowed(rootPath)) {
            return AgentToolResult("Path is outside the project: $rootPath", isError = true)
        }

        val root = File(rootPath)
        if (!root.exists()) {
            return AgentToolResult("Directory not found: $rootPath", isError = true)
        }
        if (!root.isDirectory) {
            return AgentToolResult("Not a directory: $rootPath", isError = true)
        }

        val caseInsensitive = json.optBoolean("case_insensitive", false)
        val maxDepth = json.optInt("max_depth", -1)
        val regex = wildcardToRegex(pattern, caseInsensitive)

        val hits = mutableListOf<String>()
        var stopped = false
        val sequence = root.walkTopDown()
            .onEnter { directory ->
                if (maxDepth < 0 || depthOf(directory, root) <= maxDepth) true else false
            }
            .filter { it.isFile && regex.matches(it.name) }
            .sortedBy { it.absolutePath }

        for (file in sequence) {
            if (hits.size >= MAX_HITS) {
                stopped = true
                break
            }
            hits.add(file.absolutePath)
        }

        return if (hits.isEmpty()) {
            AgentToolResult("No files match \"$pattern\"")
        } else {
            val body = hits.joinToString("\n")
            val withNote = if (stopped) {
                "$body\n[Stopped at $MAX_HITS files; narrow the pattern or the path]"
            } else {
                body
            }
            AgentToolResult(
                truncateWithNote(
                    withNote,
                    ToolLimits.MAX_TEXT_RESULT_LENGTH,
                    "Truncated at ${ToolLimits.MAX_TEXT_RESULT_LENGTH} characters"
                )
            )
        }
    }

    override fun summarize(arguments: String): String {
        val json = parseToolArguments(arguments)
        val pattern = json?.optString("pattern").orEmpty()
        val path = json?.optString("path").orEmpty()
        return if (path.isBlank()) pattern else "$pattern in $path"
    }

    /** How many levels below [root] the [directory] sits; [root] itself is level 0. */
    private fun depthOf(directory: File, root: File): Int {
        var depth = 0
        var current: File? = directory
        while (current != null && current != root) {
            depth++
            current = current.parentFile
        }
        return depth
    }

    private fun wildcardToRegex(pattern: String, ignoreCase: Boolean): Regex {
        val sb = StringBuilder("^")
        for (ch in pattern) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        sb.append('$')
        return if (ignoreCase) {
            Regex(sb.toString(), RegexOption.IGNORE_CASE)
        } else {
            Regex(sb.toString())
        }
    }

    private companion object {
        const val NAME = "find_files"
        const val MAX_HITS = 200
    }
}