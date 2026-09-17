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

package com.tom.rv2ide.artificial.agents

/** The reply protocol shared by the writing rules and the parsers. */
object AgentProtocol {

    /** Opens a file block: `@Anplatonc@file: /absolute/path`. */
    const val FILE_BLOCK_PREFIX = "@Anplatonc@file:"

    /** Closes a file block; appears alone at the start of its line. */
    const val FILE_BLOCK_END = "@Anplatonc@endfile"
}

sealed interface AgentStreamEvent {

    /** Prose, exactly as written. */
    data class TextDelta(val text: String) : AgentStreamEvent

    /** A file block that reached its closing delimiter, with the content it carried. */
    data class FileCompleted(val filePath: String, val content: String) : AgentStreamEvent
}

/**
 * Splits an agent reply into prose and file blocks.
 *
 * Feeding the whole reply through [accept] then [finish] yields the same result as streaming it
 * delta by delta, so the live view and the batch pass cannot disagree about where a block starts.
 * A block's extent is known the moment its closing line arrives, which is why the protocol requires
 * that line rather than a "next block follows" convention.
 *
 * A block that never gets its closing line is dropped, matching a reply that simply stopped.
 */
class AgentStreamParser {

    private val line = StringBuilder()
    private var inFile = false
    private var filePath: String? = null
    private val fileContent = StringBuilder()

    fun accept(delta: String, emit: (AgentStreamEvent) -> Unit) {
        for (ch in delta) {
            if (ch == '\n') {
                handleLine(line.toString(), emit)
                line.setLength(0)
            } else {
                line.append(ch)
            }
        }
    }

    fun finish(emit: (AgentStreamEvent) -> Unit) {
        if (line.isNotEmpty()) {
            handleLine(line.toString(), emit)
            line.setLength(0)
        }
        inFile = false
        filePath = null
        fileContent.setLength(0)
    }

    fun parseAll(text: String): List<AgentStreamEvent> {
        val events = mutableListOf<AgentStreamEvent>()
        accept(text) { events.add(it) }
        finish { events.add(it) }
        return events
    }

    private fun handleLine(line: String, emit: (AgentStreamEvent) -> Unit) {
        when {
            line.startsWith(AgentProtocol.FILE_BLOCK_PREFIX) -> {
                inFile = true
                filePath = line.substringAfter(AgentProtocol.FILE_BLOCK_PREFIX).trim()
                fileContent.setLength(0)
            }
            line.trim() == AgentProtocol.FILE_BLOCK_END -> {
                val path = filePath
                if (path != null) {
                    emit(AgentStreamEvent.FileCompleted(path, fileContent.toString().trim()))
                }
                inFile = false
                filePath = null
                fileContent.setLength(0)
            }
            inFile -> fileContent.append(line).append("\n")
            else -> emit(AgentStreamEvent.TextDelta(line + "\n"))
        }
    }
}