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

package com.tom.rv2ide.handlers

import androidx.lifecycle.LifecycleCoroutineScope
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.AgentSegment
import com.tom.rv2ide.artificial.agents.AgentStreamEvent
import com.tom.rv2ide.artificial.chat.ChatBlock
import com.tom.rv2ide.artificial.chat.ChatMessageStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drives one AI request and projects its callbacks onto the transcript.
 *
 * Callbacks arrive on background threads; each one writes straight into [ChatMessageStore], which
 * publishes a `StateFlow` the fragment collects on the main thread. The previous version took eleven
 * view and adapter references in its constructor and mutated them from here, which is what let a
 * progress update overwrite the answer.
 *
 * ## How callbacks map onto the transcript
 *
 * A request becomes one [ChatMessageStore.beginRequest] (the prompt, plus an empty answer), then:
 *
 * - `onProcessing` / `onRetry` -> the single progress line, updated in place.
 * - `onFileModifying` -> a pending file row appended to the answer.
 * - `onFileModified` -> ignored; the row is finalised in one step by `onSuccess`. Reporting each
 *   write twice is what used to produce duplicated rows.
 * - `onToolCallStarted` / `onToolCallFinished` -> a tool row, pending and then filled in.
 * - `onFileChangeCompleted` -> finalises a single file row in tool mode; the fallback path still
 *   finalises through `onSuccess`.
 * - `onSuccess` -> the whole reply, prose and file rows in the order the agent produced them.
 * - `onTextResponse` -> the same, for a reply that wrote no files.
 * - `onError` -> an error entry.
 */
class AIRequestHandler(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val aiAgent: AIAgentManager,
    private val messages: ChatMessageStore
) {
    private var executionJob: Job? = null

    fun execute(userRequest: String) {
        executionJob?.cancel()
        messages.beginRequest(userRequest)

        executionJob = lifecycleScope.launch {
            try {
                executeAIRequest(userRequest)
            } catch (e: Exception) {
                messages.addError("❌ Error: ${e.message}")
            } finally {
                // Also runs on cancellation, so an interrupted request cannot leave the progress line
                // behind or keep the composer disabled.
                messages.finishRequest()
            }
        }
    }

    private suspend fun executeAIRequest(userRequest: String) {
        aiAgent.executeRequest(userRequest, object : AIAgentManager.AIAgentCallback {

            override fun onProcessing(message: String) {
                messages.setStatus(message)
            }

            override fun onFileModifying(filePath: String, fileName: String) {
                messages.addPendingFileChange(filePath)
            }

            override fun onFileModified(filePath: String, fileName: String, success: Boolean) {
                // Finalised by onSuccess, which also carries the written content.
            }

            override fun onStreamEvent(event: AgentStreamEvent) {
                when (event) {
                    is AgentStreamEvent.TextDelta -> messages.appendProse(event.text)
                    is AgentStreamEvent.ThinkingDelta -> messages.appendThinking(event.text)
                    is AgentStreamEvent.FileCompleted -> Unit
                }
            }

            override fun onToolCallStarted(
                callId: String,
                toolName: String,
                summary: String,
                arguments: String
            ) {
                messages.addToolCall(callId, toolName, summary, arguments)
            }

            override fun onToolCallFinished(callId: String, result: String, isError: Boolean) {
                messages.completeToolCall(callId, result, isError)
            }

            override fun onFileChangeCompleted(
                filePath: String,
                success: Boolean,
                previousContent: String?,
                newContent: String
            ) {
                messages.completeFileChange(filePath, previousContent, newContent, success)
            }

            override fun onSuccess(
                modifications: List<AgentSegment>,
                results: List<AIAgentManager.ModificationResult>,
                summary: AIAgentManager.ModificationSummary
            ) {
                messages.completeAnswer(modifications.map { it.toBlock() })
            }

            override fun onTextResponse(
                modifications: List<AgentSegment>,
                summary: AIAgentManager.ModificationSummary
            ) {
                messages.completeAnswer(modifications.map { it.toBlock() })
            }

            override fun onError(message: String) {
                messages.addError(message)
            }

            override fun onRetry(attemptNumber: Int, message: String) {
                messages.setStatus("🔄 Retry #$attemptNumber: $message")
            }
        })
    }

    /**
     * Projects one parsed segment onto the transcript.
     *
     * The agent emits a single ordered sequence — prose and file writes interleaved — and that
     * order is what the user saw the agent produce, so the blocks are appended as they come rather
     * than reordered into "files first, then prose".
     */
    private fun AgentSegment.toBlock(): ChatBlock = when (this) {
        is AgentSegment.Text -> ChatBlock.Text(markdown)
        is AgentSegment.Thinking -> ChatBlock.Thinking(markdown)
        is AgentSegment.FileChange -> ChatBlock.FileChange(
            filePath = filePath,
            success = writeResult is com.tom.rv2ide.artificial.file.FileWriteResult.Success,
            previousContent = previousContent,
            newContent = content
        )
    }

    fun cancel() {
        executionJob?.cancel()
    }
}