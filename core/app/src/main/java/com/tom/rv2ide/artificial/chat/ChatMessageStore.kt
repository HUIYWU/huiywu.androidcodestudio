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
 * providers' in-memory history already did. Persisting it is a separate change.
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
            ChatMessage.Assistant(assistantId, now(), emptyList())
        _isProcessing.value = true
    }

    /** Shows/updates the single progress line, creating it on first use. */
    fun setStatus(text: String) {
        val id = currentStatusId
        if (id == NO_ID) {
            val statusId = nextId.getAndIncrement()
            currentStatusId = statusId
            _messages.value = _messages.value + ChatMessage.Status(statusId, now(), text)
        } else {
            _messages.value = _messages.value.map { message ->
                if (message is ChatMessage.Status && message.id == id) {
                    message.copy(text = text)
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

    /**
     * Appends a placeholder row for a file that is about to be written.
     *
     * Printed as soon as the provider reports the file, so a long write is visible instead of the
     * UI appearing frozen. [completeAnswer] replaces these with the final, ordered blocks.
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
     * Appends streamed prose to the answer, growing the trailing text block.
     *
     * Deltas arrive far faster than the list can be rebuilt, so consecutive text lands in one block
     * rather than one block per delta. [completeAnswer] later replaces the whole list with the final
     * ordered blocks, so this is a live view and not the source of truth for ordering.
     */
    fun appendProse(text: String) {
        if (text.isEmpty()) return
        val id = currentAssistantId
        if (id == NO_ID) return
        _messages.value = _messages.value.map { message ->
            if (message is ChatMessage.Assistant && message.id == id) {
                val last = message.blocks.lastOrNull()
                val blocks = if (last is ChatBlock.Text) {
                    message.blocks.dropLast(1) + last.copy(markdown = last.markdown + text)
                } else {
                    message.blocks + ChatBlock.Text(text)
                }
                message.copy(blocks = blocks)
            } else {
                message
            }
        }
    }

    /**
     * Appends streamed reasoning to the answer, growing the trailing thinking block.
     *
     * Grows in place for the same reason as [appendProse]: reasoning deltas are just as frequent, and
     * one block per delta would rebuild the list on every one of them.
     */
    fun appendThinking(text: String) {
        if (text.isEmpty()) return
        val id = currentAssistantId
        if (id == NO_ID) return
        _messages.value = _messages.value.map { message ->
            if (message is ChatMessage.Assistant && message.id == id) {
                val last = message.blocks.lastOrNull()
                val blocks = if (last is ChatBlock.Thinking) {
                    message.blocks.dropLast(1) + last.copy(markdown = last.markdown + text)
                } else {
                    message.blocks + ChatBlock.Thinking(text)
                }
                message.copy(blocks = blocks)
            } else {
                message
            }
        }
    }

    /**
     * Appends a pending row for a tool call the agent has started.
     *
     * The row is completed later by [completeToolCall], once the result is known.
     */
    fun addToolCall(callId: String, toolName: String, summary: String, arguments: String) {
        val id = currentAssistantId
        if (id == NO_ID) return
        _messages.value = _messages.value.map { message ->
            if (message is ChatMessage.Assistant && message.id == id) {
                message.copy(
                    blocks = message.blocks + ChatBlock.ToolCall(
                        callId = callId,
                        toolName = toolName,
                        summary = summary,
                        arguments = arguments
                    )
                )
            } else {
                message
            }
        }
    }

    /** Fills in the result of the row opened by [addToolCall]; a no-op if the row is gone. */
    fun completeToolCall(callId: String, result: String, isError: Boolean) {
        val id = currentAssistantId
        if (id == NO_ID) return
        _messages.value = _messages.value.map { message ->
            if (message is ChatMessage.Assistant && message.id == id) {
                message.copy(
                    blocks = message.blocks.map { block ->
                        if (block is ChatBlock.ToolCall && block.callId == callId &&
                            block.result == null
                        ) {
                            block.copy(result = result, isError = isError)
                        } else {
                            block
                        }
                    }
                )
            } else {
                message
            }
        }
    }

    /**
     * Finalises the pending file row for [filePath] with what was actually written.
     *
     * Tool mode's counterpart of [completeAnswer] for a single write: the loop reports each write as
     * it happens, long before the final answer, so the row cannot wait for the whole answer to be
     * replaced.
     */
    fun completeFileChange(
        filePath: String,
        previousContent: String?,
        newContent: String,
        success: Boolean
    ) {
        val id = currentAssistantId
        if (id == NO_ID) return
        _messages.value = _messages.value.map { message ->
            if (message is ChatMessage.Assistant && message.id == id) {
                message.copy(
                    blocks = message.blocks.map { block ->
                        if (block is ChatBlock.FileChange && block.pending &&
                            block.filePath == filePath
                        ) {
                            ChatBlock.FileChange(
                                filePath = filePath,
                                success = success,
                                previousContent = previousContent,
                                newContent = newContent
                            )
                        } else {
                            block
                        }
                    }
                )
            } else {
                message
            }
        }
    }

    /**
     * Replaces the answer's blocks with the final ones, in the order the agent produced them.
     *
     * Called from `onSuccess` / `onTextResponse`, which is the first point where prose and file
     * writes are both known. The whole list is set rather than appended to, because the pending
     * rows added by [addPendingFileChange] carry no prose and so cannot represent that order.
     */
    fun completeAnswer(blocks: List<ChatBlock>) {
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
     * Finalises rows that were still in flight as failed.
     *
     * Called when a request ends for any reason; rows that completed keep their result, and rows
     * that never started have no row at all. A write cannot be cut short mid-file, so a pending row
     * means the call was abandoned before it reported back.
     */
    fun failPendingBlocks(interruptedText: String) {
        val id = currentAssistantId
        if (id == NO_ID) return
        _messages.value = _messages.value.map { message ->
            if (message is ChatMessage.Assistant && message.id == id) {
                message.copy(
                    blocks = message.blocks.map { block ->
                        when {
                            block is ChatBlock.ToolCall && block.result == null ->
                                block.copy(result = interruptedText, isError = true)
                            block is ChatBlock.FileChange && block.pending ->
                                block.copy(pending = false, success = false)
                            else -> block
                        }
                    }
                )
            } else {
                message
            }
        }
    }

    /**
     * Closes the request: drops the progress line and discards the answer bubble if the agent
     * produced nothing at all.
     */
    fun finishRequest() {
        clearStatus()
        val id = currentAssistantId
        currentAssistantId = NO_ID
        _messages.value = _messages.value
            .mapNotNull { message ->
                if (message is ChatMessage.Assistant && message.id == id) {
                    message.takeIf { it.blocks.isNotEmpty() }
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
