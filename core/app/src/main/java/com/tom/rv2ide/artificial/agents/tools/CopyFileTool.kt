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

class CopyFileTool(private val isPathAllowed: (String) -> Boolean) : AgentTool {

    override val spec = AgentToolSpec(
        name = NAME,
        description = "Copy a file or directory. Fails when the destination already exists; create its parent directory first when missing.",
        parameters = JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    put(
                        "source",
                        JSONObject().apply {
                            put("type", "string")
                            put("description", "Absolute path of the file or directory to copy.")
                        }
                    )
                    put(
                        "destination",
                        JSONObject().apply {
                            put("type", "string")
                            put("description", "Absolute path to copy it to.")
                        }
                    )
                }
            )
            put("required", JSONArray().put("source").put("destination"))
        }
    )

    override suspend fun execute(arguments: String): AgentToolResult {
        val json = parseToolArguments(arguments)
            ?: return AgentToolResult("Invalid arguments: ${arguments.take(200)}", isError = true)
        val source = json.optString("source")
        val destination = json.optString("destination")
        if (source.isBlank() || destination.isBlank()) {
            return AgentToolResult("Missing required parameter: source or destination", isError = true)
        }
        if (!isPathAllowed(source)) {
            return AgentToolResult("Path is outside the project: $source", isError = true)
        }
        if (!isPathAllowed(destination)) {
            return AgentToolResult("Path is outside the project: $destination", isError = true)
        }

        val sourceFile = File(source)
        if (!sourceFile.exists()) {
            return AgentToolResult("Source not found: $source", isError = true)
        }
        val destinationFile = File(destination)
        if (destinationFile.exists()) {
            return AgentToolResult("Destination already exists: $destination", isError = true)
        }
        val parent = destinationFile.parentFile
        if (parent != null && !parent.exists()) {
            return AgentToolResult(
                "Parent directory does not exist: ${parent.absolutePath}. Use make_directory first.",
                isError = true
            )
        }

        return try {
            if (sourceFile.isDirectory) {
                sourceFile.copyRecursively(destinationFile, overwrite = false)
            } else {
                sourceFile.copyTo(destinationFile, overwrite = false)
            }
            AgentToolResult("Copied: $source -> $destination")
        } catch (e: Exception) {
            AgentToolResult("Failed to copy: ${e.message}", isError = true)
        }
    }

    override fun summarize(arguments: String): String {
        val json = parseToolArguments(arguments) ?: return ""
        val source = json.optString("source")
        val destination = json.optString("destination")
        return "$source -> $destination"
    }

    private companion object {
        const val NAME = "copy_file"
    }
}