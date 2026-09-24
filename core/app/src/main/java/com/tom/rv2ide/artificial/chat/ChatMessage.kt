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

package com.tom.rv2ide.artificial.chat

/**
 * One entry of the AI sidebar transcript.
 *
 * Replaces the previous single-`TextView` output, which had to serve as both the progress line and
 * the answer area: a status update overwrote the answer, and the next answer overwrote the status.
 * Every kind of output now has its own entry, so nothing is lost.
 *
 * @see ChatMessageStore for ownership and lifetime.
 */
sealed interface ChatMessage {

    val id: Long
    val timestamp: Long

    /** The prompt the user sent. */
    data class User(
        override val id: Long,
        override val timestamp: Long,
        val text: String
    ) : ChatMessage

    /**
     * One answer. [blocks] holds prose and file changes together so they keep the order the agent
     * produced them in.
     */
    data class Assistant(
        override val id: Long,
        override val timestamp: Long,
        val blocks: List<ChatBlock> = emptyList()
    ) : ChatMessage

    /**
     * A compression event: the older turns were folded into [text], and the model now receives that
     * summary instead of them. Rendered as a collapsed row, so the user can read what it was told.
     */
    data class ContextSummary(
        override val id: Long,
        override val timestamp: Long,
        val text: String
    ) : ChatMessage

    /**
     * Progress line ("Analyzing your request...", "Retry #2...").
     *
     * Updated in place (same [id]) rather than appended, so a single request produces at most one
     * progress line. Provider switching emits several of these in a row; appending them all would
     * fill the transcript with noise.
     */
    data class Status(
        override val id: Long,
        override val timestamp: Long,
        val text: String
    ) : ChatMessage

    data class Error(
        override val id: Long,
        override val timestamp: Long,
        val text: String
    ) : ChatMessage
}

/**
 * A piece of a [ChatMessage.Assistant] answer.
 */
sealed interface ChatBlock {

    /** Prose, rendered as Markdown. */
    data class Text(val markdown: String) : ChatBlock

    /**
     * The reasoning the model reported before answering, rendered as a collapsed block.
     *
     * Rendered in the muted colour so it reads as context for the answer rather than as the answer.
     */
    data class Thinking(val markdown: String) : ChatBlock

    /**
     * A file the agent rewrote, rendered inline in the transcript as a collapsible row.
     *
     * This is what replaced the separate "Modification Summary" card plus file list: both described
     * exactly this information, outside of the answer that produced it.
     *
     * @param previousContent what the file held immediately before this write, or `null` when the
     *   file did not exist then. The diff is taken against this, so reverting a change reads as the
     *   removal of that change rather than as nothing happening.
     * @param newContent content as written.
     * @param pending true while the write is still in flight; the row shows a progress indicator and
     *   must not be expanded (there is no final content yet).
     */
    data class FileChange(
        val filePath: String,
        val success: Boolean,
        val previousContent: String?,
        val newContent: String,
        val pending: Boolean = false
    ) : ChatBlock {

        val fileName: String get() = filePath.substringAfterLast('/')
    }

    /**
     * A tool call the agent made, rendered as a collapsed row.
     *
     * The header carries the tool name and [summary]; expanding shows the raw [arguments] and, once
     * the call has finished, its [result]. [callId] ties the row to the call so the result can be
     * filled in place when it arrives; [result] is null until then.
     */
    data class ToolCall(
        val callId: String,
        val toolName: String,
        val summary: String,
        val arguments: String,
        val result: String? = null,
        val isError: Boolean = false
    ) : ChatBlock
}
