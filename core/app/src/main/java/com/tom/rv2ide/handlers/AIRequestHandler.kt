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

package com.tom.rv2ide.handlers

import androidx.lifecycle.LifecycleCoroutineScope
import com.tom.rv2ide.artificial.agents.AIAgentManager
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
 * - `onSuccess` -> the answer's file rows and the prose that precedes them.
 * - `onTextResponse` -> the answer's single prose block.
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

            override fun onSuccess(
                response: String,
                modifications: List<AIAgentManager.ModificationResult>,
                summary: AIAgentManager.ModificationSummary
            ) {
                messages.completeFileChanges(
                    modifications.map { modification ->
                        ChatBlock.FileChange(
                            filePath = modification.filePath,
                            success = modification.success,
                            // The pre-write content is not carried out of AIAgentManager yet, so the
                            // row can only show what was written. Comparing the two is the follow-up
                            // change.
                            previousContent = null,
                            newContent = modification.content
                        )
                    }
                )
                messages.addTextBlock(proseOf(response))
            }

            override fun onTextResponse(
                response: String,
                summary: AIAgentManager.ModificationSummary
            ) {
                messages.addTextBlock(response)
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
     * The agent protocol embeds whole file bodies in the reply, behind `FILE_TO_MODIFY:` markers. The
     * answer to "what did you do" is the prose that precedes the first marker; everything from there
     * on is represented by the file rows instead, so the raw bodies are dropped from the transcript.
     */
    private fun proseOf(response: String): String {
        val marker = response.indexOf("FILE_TO_MODIFY:")
        return if (marker >= 0) response.substring(0, marker).trim() else response.trim()
    }

    fun cancel() {
        executionJob?.cancel()
    }
}