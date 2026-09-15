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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory transcript of the AI sidebar chat.
 *
 * Ownership: held by `AISharedViewModel` (activity-scoped), so the transcript survives fragment view
 * recreation — a configuration change such as a theme switch no longer discards the conversation.
 *
 * Known limitation: nothing is persisted. Process death loses the transcript, exactly as the
 * providers' in-memory `conversationHistory` already did. Persisting it is a separate change.
 *
 * A request is assumed to be the only one in flight: `AIRequestHandler` cancels the previous job
 * before starting a new one, and `AIAgentCallback` carries no message identifier — so "the current
 * answer" is unambiguous.
 */
class ChatMessageStore {

    private val nextId = AtomicLong(1L)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)

    /** True while a request is in flight; the send button is disabled for that window. */
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    /** Id of the answer currently being written, or [NO_ID]. */
    private var currentAssistantId: Long = NO_ID

    /** Id of the single progress line, or [NO_ID]. */
    private var currentStatusId: Long = NO_ID

    fun beginRequest(prompt: String) {
        currentStatusId = NO_ID
        val message = ChatMessage.User(nextId.getAndIncrement(), now(), prompt)
        val assistantId = nextId.getAndIncrement()
        currentAssistantId = assistantId
        _messages.value = _messages.value + message +
            ChatMessage.Assistant(assistantId, now(), emptyList(), isBusy = true)
        _isProcessing.value = true
    }

    /** Shows/updates the single progress line, creating it on first use. */
    fun setStatus(text: String, busy: Boolean = true) {
        val id = currentStatusId
        if (id == NO_ID) {
            val statusId = nextId.getAndIncrement()
            currentStatusId = statusId
            _messages.value = _messages.value + ChatMessage.Status(statusId, now(), text, busy)
        } else {
            _messages.value = _messages.value.map { message ->
                if (message is ChatMessage.Status && message.id == id) {
                    message.copy(text = text, isBusy = busy)
                } else {
                    message
                }
            }
        }
    }

    /** Removes the progress line, if present. */
    fun clearStatus() {
        val id = currentStatusId
        if (id == NO_ID) return
        currentStatusId = NO_ID
        _messages.value = _messages.value.filterNot {
            it is ChatMessage.Status && it.id == id
        }
    }

    /** Replaces the current answer's blocks. No-op if no request is in flight. */
    fun setAnswerBlocks(blocks: List<ChatBlock>) {
        val id = currentAssistantId
        if (id == NO_ID) return
        _messages.value = _messages.value.map { message ->
            if (message is ChatMessage.Assistant && message.id == id) {
                message.copy(blocks = blocks)
            } else {
                message
            }
        }
    }

    /**
     * Appends a placeholder row for a file that is about to be written.
     *
     * Printed as soon as the provider reports the file, so a long write is visible instead of the
     * UI appearing frozen. [completeFileChanges] replaces these with the final content.
     */
    fun addPendingFileChange(filePath: String) {
        val id = currentAssistantId
        if (id == NO_ID) return
        _messages.value = _messages.value.map { message ->
            if (message is ChatMessage.Assistant && message.id == id) {
                message.copy(
                    blocks = message.blocks + ChatBlock.FileChange(
                        filePath = filePath,
                        success = true,
                        previousContent = null,
                        newContent = "",
                        pending = true
                    )
                )
            } else {
                message
            }
        }
    }

    /**
     * Replaces every pending file row with the final ones.
     *
     * Called from `onSuccess`, which is the first point where the written content and the pre-write
     * content are both available.
     */
    fun completeFileChanges(changes: List<ChatBlock.FileChange>) {
        val id = currentAssistantId
        if (id == NO_ID) return
        _messages.value = _messages.value.map { message ->
            if (message is ChatMessage.Assistant && message.id == id) {
                message.copy(blocks = message.blocks.filterNot { it is ChatBlock.FileChange } + changes)
            } else {
                message
            }
        }
    }

    /** Appends a prose block to the current answer. */
    fun addTextBlock(markdown: String) {
        if (markdown.isBlank()) return
        val id = currentAssistantId
        if (id == NO_ID) return
        _messages.value = _messages.value.map { message ->
            if (message is ChatMessage.Assistant && message.id == id) {
                message.copy(blocks = message.blocks + ChatBlock.Text(markdown))
            } else {
                message
            }
        }
    }

    /**
     * Closes the request: drops the progress line, clears the busy flag and discards the answer
     * bubble if the agent produced nothing at all.
     */
    fun finishRequest() {
        clearStatus()
        val id = currentAssistantId
        currentAssistantId = NO_ID
        _messages.value = _messages.value
            .mapNotNull { message ->
                if (message is ChatMessage.Assistant && message.id == id) {
                    if (message.blocks.isEmpty()) null else message.copy(isBusy = false)
                } else {
                    message
                }
            }
        _isProcessing.value = false
    }

    fun addError(text: String) {
        clearStatus()
        _messages.value = _messages.value + ChatMessage.Error(nextId.getAndIncrement(), now(), text)
        _isProcessing.value = false
    }

    /** Drops the transcript. Called by "clear conversation". */
    fun clear() {
        currentAssistantId = NO_ID
        currentStatusId = NO_ID
        _messages.value = emptyList()
        _isProcessing.value = false
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val NO_ID = -1L
    }
}
