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

class SearchTool(
    private val isPathAllowed: (String) -> Boolean,
    private val projectRoot: File?
) : AgentTool {

    override val spec = AgentToolSpec(
        name = NAME,
        description = "Search project files for a text fragment, case-insensitively. Kotlin, Java, XML, Gradle and script files are searched.",
        parameters = JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    put(
                        "query",
                        JSONObject().apply {
                            put("type", "string")
                            put("description", "Text to look for.")
                        }
                    )
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
                }
            )
            put("required", JSONArray().put("query"))
        }
    )

    override suspend fun execute(arguments: String): AgentToolResult {
        val json = parseToolArguments(arguments)
            ?: return AgentToolResult("Invalid arguments: ${arguments.take(200)}", isError = true)
        val query = json.optString("query")
        if (query.isBlank()) {
            return AgentToolResult("Missing required parameter: query", isError = true)
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

        val hits = mutableListOf<String>()
        var stopped = false
        val files = root.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension in EXTENSIONS }
            .filter { !it.path.contains("/build/") && !it.path.contains("/.gradle/") }
            .sortedBy { it.absolutePath }

        search@ for (file in files) {
            val lines = try {
                file.readLines()
            } catch (e: Exception) {
                continue
            }
            for ((index, line) in lines.withIndex()) {
                if (hits.size >= MAX_HITS) {
                    stopped = true
                    break@search
                }
                if (line.contains(query, ignoreCase = true)) {
                    hits.add("${file.absolutePath}:${index + 1}: ${line.trim().take(300)}")
                }
            }
        }

        return if (hits.isEmpty()) {
            AgentToolResult("No matches for \"$query\"")
        } else {
            val body = hits.joinToString("\n")
            AgentToolResult(
                if (stopped) "$body\n[Stopped at $MAX_HITS matches; narrow the query or the path]"
                else body
            )
        }
    }

    override fun summarize(arguments: String): String {
        val json = parseToolArguments(arguments)
        val query = json?.optString("query").orEmpty()
        val path = json?.optString("path").orEmpty()
        return if (path.isBlank()) "\"$query\"" else "\"$query\" in $path"
    }

    private companion object {
        const val NAME = "search"
        const val MAX_HITS = 50
        val EXTENSIONS = setOf("kt", "java", "xml", "gradle", "kts")
    }
}