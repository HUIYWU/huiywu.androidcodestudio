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

import android.content.Context
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.file.FileWriteResult

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

interface AIAgent {
    val providerId: String
    val providerName: String

    /** Cross-request conversation history; see [AgentHistory]. */
    val history: AgentHistory
    
    fun initialize(apiKey: String, context: Context)
    fun reinitializeWithNewModel(apiKey: String, context: Context)
    fun setContext(context: Context)
    fun setProjectData(projectTreeResult: ProjectTreeResult)
    fun clearConversation()
    suspend fun generateCode(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?
    ): Result<String>

    /**
     * Same request as [generateCode], but reports prose as it arrives.
     *
     * The returned text is the full reply, used for the conversation history and for the final
     * parse, so a provider that cannot stream can answer by calling [generateCode] and replaying
     * the result through the parser.
     *
     * Only [AgentStreamEvent.TextDelta] is guaranteed to arrive incrementally; a
     * [AgentStreamEvent.FileCompleted] may be reported late, because a file block is not complete
     * until its closing delimiter has been read.
     */
    suspend fun generateCodeStreaming(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?,
        onEvent: (AgentStreamEvent) -> Unit
    ): Result<String> {
        val result = generateCode(prompt, context, language, projectStructure)
        result.getOrNull()?.let { reply ->
            AgentStreamParser().parseAll(reply).forEach(onEvent)
        }
        return result
    }

    /**
     * One round of a tool-aware conversation.
     *
     * [messages] is the running conversation — system prompt, history and the user request, plus
     * every tool round-trip of this request; [tools] are the tools the provider may call. The round's
     * text and any tool calls the model asked for are returned together; the manager executes the
     * calls and then requests the next round.
     *
     * The default marks the provider as not tool-aware: the request then falls back to the text
     * protocol ([generateCodeStreaming]).
     */
    suspend fun generateTurn(
        messages: List<AgentMessage>,
        tools: List<AgentToolSpec>,
        onEvent: (AgentStreamEvent) -> Unit
    ): Result<AgentTurn> =
        Result.failure(ToolsNotSupportedException("$providerName does not support tool calling"))
    
    
    fun recordModification(filePath: String, oldContent: String?, newContent: String, success: Boolean)
    fun undoLastModification(): Boolean
    fun getModificationHistory(): List<ModificationAttempt>
    
    fun resetAttemptCount()
    fun incrementAttemptCount()
    fun getCurrentAttemptCount(): Int
    fun canRetry(): Boolean
    
    fun writeFile(filePath: String, content: String): FileWriteResult
    fun isInitialized(): Boolean
}

data class ModificationAttempt(
    val timestamp: Long,
    val filePath: String,
    val previousContent: String?,
    val newContent: String,
    val attemptNumber: Int = 0,
    val success: Boolean = false
)

/**
 * Returned by the default [AIAgent.generateTurn]: the provider cannot call tools, so the request
 * answers through the text protocol instead.
 */
class ToolsNotSupportedException(message: String) : UnsupportedOperationException(message)