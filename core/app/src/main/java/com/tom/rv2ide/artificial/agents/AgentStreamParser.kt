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

    /**
     * Reasoning the provider streams alongside the reply, in a field of its own.
     *
     * Carried as its own event rather than through the text protocol: it never appears in the reply
     * text, so no delimiter can find it, and the API requires it to stay out of the conversation
     * history. It therefore takes no part in deciding where a block starts.
     */
    data class ThinkingDelta(val text: String) : AgentStreamEvent

    /** A file block that reached its closing delimiter, with the content it carried. */
    data class FileCompleted(val filePath: String, val content: String) : AgentStreamEvent
}

/**
 * Splits an agent reply into prose and file blocks.
 *
 * Feeding the whole reply through [accept] then [finish] yields the same text as streaming it delta
 * by delta, so the live view and the batch pass cannot disagree about where a block starts. A block's
 * extent is known the moment its closing line arrives, which is why the protocol requires that line
 * rather than a "next block follows" convention.
 *
 * Prose is held back only at a line start, and only while that start can still become the opening
 * delimiter; once it cannot, its characters go out on the delta that produced them. Holding whole
 * lines instead would stall the live view for as long as a line takes to be written. A block's own
 * content is buffered until the block closes, which costs nothing because it is never shown as it
 * arrives.
 *
 * A block that never gets its closing line is dropped, matching a reply that simply stopped.
 */
class AgentStreamParser {

    /** Line-start characters, kept while they can still turn out to be the opening delimiter. */
    private val lineStart = StringBuilder()

    /** Prose that is already known not to belong to a delimiter, waiting to be handed over. */
    private val prose = StringBuilder()

    /** The content line being read inside a block, held so the closing line can be recognised. */
    private val contentLine = StringBuilder()

    private val fileContent = StringBuilder()

    private var atLineStart = true
    private var inPathLine = false
    private var inFile = false
    private var filePath: String? = null

    fun accept(delta: String, emit: (AgentStreamEvent) -> Unit) {
        for (ch in delta) {
            when {
                inFile -> consumeFileChar(ch, emit)
                inPathLine -> consumePathChar(ch)
                atLineStart -> consumeLineStartChar(ch, emit)
                else -> consumeProseChar(ch, emit)
            }
        }
        flushProse(emit)
    }

    fun finish(emit: (AgentStreamEvent) -> Unit) {
        flushProse(emit)
        // A held line start that never became a delimiter is prose that was simply not finished; a
        // path line or block without its closing line is dropped, matching a reply that stopped.
        if (!inPathLine && !inFile && lineStart.isNotEmpty()) {
            emit(AgentStreamEvent.TextDelta(lineStart.toString()))
        }
        lineStart.setLength(0)
        prose.setLength(0)
        contentLine.setLength(0)
        fileContent.setLength(0)
        atLineStart = true
        inPathLine = false
        inFile = false
        filePath = null
    }

    fun parseAll(text: String): List<AgentStreamEvent> {
        val events = mutableListOf<AgentStreamEvent>()
        accept(text) { events.add(it) }
        finish { events.add(it) }
        return events
    }

    /** Reads the rest of the line that opened a block; it ends at the first newline. */
    private fun consumePathChar(ch: Char) {
        if (ch == '\n') {
            filePath = lineStart.toString().trim()
            lineStart.setLength(0)
            inPathLine = false
            inFile = true
        } else {
            lineStart.append(ch)
        }
    }

    /**
     * Reads one character of a block's body.
     *
     * The body is only committed line by line so that the closing delimiter can be recognised; the
     * delimiter itself is never part of the content.
     */
    private fun consumeFileChar(ch: Char, emit: (AgentStreamEvent) -> Unit) {
        if (ch != '\n') {
            contentLine.append(ch)
            return
        }

        val line = contentLine.toString()
        contentLine.setLength(0)

        if (line.trim() == AgentProtocol.FILE_BLOCK_END) {
            val path = filePath
            if (path != null) {
                emit(AgentStreamEvent.FileCompleted(path, fileContent.toString().trim()))
            }
            fileContent.setLength(0)
            inFile = false
            filePath = null
            atLineStart = true
        } else {
            fileContent.append(line).append('\n')
        }
    }

    /** Reads a line start, holding it only while it can still become the opening delimiter. */
    private fun consumeLineStartChar(ch: Char, emit: (AgentStreamEvent) -> Unit) {
        if (ch == '\n') {
            emit(AgentStreamEvent.TextDelta(lineStart.toString() + ch))
            lineStart.setLength(0)
            return
        }

        lineStart.append(ch)
        when {
            lineStart.startsWith(AgentProtocol.FILE_BLOCK_PREFIX) -> {
                lineStart.setLength(0)
                atLineStart = false
                inPathLine = true
            }
            !AgentProtocol.FILE_BLOCK_PREFIX.startsWith(lineStart) -> {
                atLineStart = false
                prose.append(lineStart)
                lineStart.setLength(0)
            }
        }
    }

    private fun consumeProseChar(ch: Char, emit: (AgentStreamEvent) -> Unit) {
        prose.append(ch)
        if (ch == '\n') {
            flushProse(emit)
            atLineStart = true
        }
    }

    /** Hands over the prose read so far, which by construction cannot be part of a delimiter. */
    private fun flushProse(emit: (AgentStreamEvent) -> Unit) {
        if (prose.isEmpty()) return
        val text = prose.toString()
        prose.setLength(0)
        emit(AgentStreamEvent.TextDelta(text))
    }
}