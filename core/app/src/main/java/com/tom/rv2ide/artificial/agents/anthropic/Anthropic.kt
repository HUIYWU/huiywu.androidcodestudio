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

package com.tom.rv2ide.artificial.agents.anthropic

import android.content.Context
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.AgentHistory
import com.tom.rv2ide.artificial.agents.AgentMessage
import com.tom.rv2ide.artificial.agents.AgentStreamEvent
import com.tom.rv2ide.artificial.agents.AgentToolCall
import com.tom.rv2ide.artificial.agents.AgentToolSpec
import com.tom.rv2ide.artificial.agents.AgentTurn
import com.tom.rv2ide.artificial.agents.ModificationAttempt
import com.tom.rv2ide.artificial.agents.runCancellable
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.catalog.ModelRepository
import com.tom.rv2ide.artificial.catalog.ModelSources
import com.tom.rv2ide.artificial.rules.WritingRules
import com.tom.rv2ide.artificial.secrets.ApiKey
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.exceptions.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.json.JSONArray

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class Anthropic : AIAgent {

  private var apiKey: String? = null
  private val writingRules = WritingRules.Instructions()
  private var projectTreeResult: ProjectTreeResult? = null
  private var fileWriter: AIFileWriter? = null
  override val history = AgentHistory()
  private val modificationHistory = mutableListOf<ModificationAttempt>()
  private var currentAttemptCount = 0
  private val maxRetryAttempts = 3
  private var agents: Agents? = null
  private var selectedModel: String = ModelRepository.getDefaultModel(PROVIDER_ID)
  override val providerId = PROVIDER_ID
  override val providerName = ModelSources.providerName(PROVIDER_ID)

  companion object {
      /** Id shared by the registry, the catalogue and the persisted selection. */
      const val PROVIDER_ID = "claude"

      fun registerAgent() {
          AIAgentRegistry.register(PROVIDER_ID, object : AIAgentRegistry.AgentFactory {
              override fun create(context: Context): AIAgent {
                  return Anthropic()
              }
              
              override fun hasValidApiKey(): Boolean {
                  val apiKey = getApiKey()
                  return apiKey != null && apiKey.isNotBlank() && apiKey.length > 20
              }
              
              override fun getApiKey(): String? {
                  return ApiKey.getAnthropicApiKey().takeIf { it.isNotBlank() }
              }
          })
      }
  }
        
  override fun initialize(apiKey: String, context: Context) {
      try {
          this.apiKey = apiKey
          agents = Agents(context)
          val agentsRef = agents!!
          // Resolved through the catalogue instead of a literal: a stored name the provider no
          // longer offers is replaced by its default, which is what the copies of these fallbacks
          // used to disagree about.
          selectedModel = agentsRef.resolveModel(PROVIDER_ID)
      } catch (e: Exception) {
          throw e
      }
  }

  override fun reinitializeWithNewModel(apiKey: String, context: Context) {
    initialize(apiKey, context)
  }

  override fun setContext(context: Context) {
    fileWriter = AIFileWriter(context)
  }

  override fun setProjectData(projectTreeResult: ProjectTreeResult) {
    this.projectTreeResult = projectTreeResult
  }

  override fun clearConversation() {
    history.clear()
    modificationHistory.clear()
    currentAttemptCount = 0
  }

  override fun recordModification(filePath: String, oldContent: String?, newContent: String, success: Boolean) {
    modificationHistory.add(
        ModificationAttempt(
            timestamp = System.currentTimeMillis(),
            filePath = filePath,
            previousContent = oldContent,
            newContent = newContent,
            attemptNumber = currentAttemptCount,
            success = success
        )
    )
  }

  override fun undoLastModification(): Boolean {
    if (modificationHistory.isEmpty()) return false
    
    val lastMod = modificationHistory.lastOrNull { it.success } ?: return false
    
    if (lastMod.previousContent != null) {
      val result = writeFile(lastMod.filePath, lastMod.previousContent)
      if (result is FileWriteResult.Success) {
        modificationHistory.removeAt(modificationHistory.lastIndexOf(lastMod))
        return true
      }
    } else {
      try {
        File(lastMod.filePath).delete()
        modificationHistory.removeAt(modificationHistory.lastIndexOf(lastMod))
        return true
      } catch (e: Exception) {
        return false
      }
    }
    return false
  }

  override fun getModificationHistory(): List<ModificationAttempt> {
    return modificationHistory.toList()
  }

  override fun resetAttemptCount() {
    currentAttemptCount = 0
  }

  override fun incrementAttemptCount() {
    currentAttemptCount++
  }

  override fun getCurrentAttemptCount(): Int = currentAttemptCount

  override fun canRetry(): Boolean = currentAttemptCount < maxRetryAttempts

  private fun isUserRequestingCorrection(message: String): Boolean {
    val correctionKeywords = listOf(
        "wrong", "not what", "mistake", "error", "incorrect", 
        "that's not", "not right", "fix", "undo", "revert",
        "different", "try again", "not working"
    )
    return correctionKeywords.any { message.lowercase().contains(it) }
  }

  override suspend fun generateCode(
      prompt: String,
      context: String?,
      language: String,
      projectStructure: String?,
  ): Result<String> =
      withContext(Dispatchers.IO) {
        try {
          val key = apiKey
              ?: return@withContext Result.failure(
                  IllegalStateException("Anthropic service not initialized")
              )

          val needsCorrection = isUserRequestingCorrection(prompt)

          val fullPrompt = buildString {
            append("=== PROJECT STRUCTURE (THESE ARE THE EXACT PATHS YOU MUST USE) ===\n")
            if (projectTreeResult != null) {
              append(projectTreeResult!!.tree)
              append("\n\n")
              append("CRITICAL: Use ONLY the paths shown above. Do NOT make up fake paths like '/storage/emulated/0/project' or 'com.example.yourproject'.\n")
              append("CRITICAL: Look at the actual paths above and use those EXACT paths.\n\n")
            }
            
            if (context != null) {
              append("=== ADDITIONAL CONTEXT ===\n")
              append(context)
              append("\n\n")
            }
            
            val historyEntries = history.snapshot()
            if (historyEntries.isNotEmpty()) {
              append("=== CONVERSATION HISTORY ===\n")
              historyEntries.forEach { msg ->
                append("${msg.role.uppercase()}: ${msg.content}\n\n")
              }
            }

            if (needsCorrection && modificationHistory.isNotEmpty()) {
              append("=== CORRECTION REQUIRED ===\n")
              append("The user indicated the previous modification was WRONG.\n")
              append("Previous failed attempts:\n")
              modificationHistory.takeLast(3).forEach { attempt ->
                append("Attempt ${attempt.attemptNumber}: ${attempt.filePath}\n")
                append("Result: ${if (attempt.success) "Applied but user rejected" else "Failed"}\n\n")
              }
              append("You MUST try a DIFFERENT approach. Do NOT repeat the same solution.\n")
              append("Analyze what went wrong and provide a better solution.\n\n")
            }

            if (currentAttemptCount > 0) {
              append("=== RETRY ATTEMPT $currentAttemptCount/$maxRetryAttempts ===\n")
              append("This is retry attempt number $currentAttemptCount.\n")
              append("Previous attempts did not satisfy the user.\n")
              append("Think carefully and provide a different solution.\n\n")
            }
            
            append("=== USER REQUEST ===\n")
            append(prompt)
          }

          val response = callAnthropicAPI(key, fullPrompt)

          if (response.isBlank()) {
            return@withContext Result.failure(Exception("Empty response from AI"))
          }

          Result.success(response)
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  override suspend fun generateTurn(
      messages: List<AgentMessage>,
      tools: List<AgentToolSpec>,
      onEvent: (AgentStreamEvent) -> Unit,
  ): Result<AgentTurn> =
      withContext(Dispatchers.IO) {
        try {
          val key = apiKey
              ?: return@withContext Result.failure(
                  IllegalStateException("Anthropic service not initialized")
              )

          val requestBody = buildToolRequestBody(messages, tools)
          val text = StringBuilder()
          val toolCalls = ToolUseAccumulator()

          streamToolTurn(key, requestBody) { payload ->
            when (stringOf(payload, "type")) {
              "content_block_start" -> {
                val block = payload.optJSONObject("content_block")
                if (stringOf(block, "type") == "tool_use") {
                  toolCalls.start(
                      index = payload.optInt("index", 0),
                      id = stringOf(block, "id"),
                      name = stringOf(block, "name")
                  )
                }
              }
              "content_block_delta" -> {
                val delta = payload.optJSONObject("delta")
                when (stringOf(delta, "type")) {
                  "text_delta" -> {
                    val fragment = stringOf(delta, "text")
                    if (fragment.isNotEmpty()) {
                      text.append(fragment)
                      onEvent(AgentStreamEvent.TextDelta(fragment))
                    }
                  }
                  "thinking_delta" -> {
                    val fragment = stringOf(delta, "thinking")
                    if (fragment.isNotEmpty()) {
                      onEvent(AgentStreamEvent.ThinkingDelta(fragment))
                    }
                  }
                  "input_json_delta" -> {
                    toolCalls.append(
                        index = payload.optInt("index", 0),
                        fragment = stringOf(delta, "partial_json")
                    )
                  }
                }
              }
            }
          }

          if (text.isEmpty() && !toolCalls.hasCalls()) {
            return@withContext Result.failure(Exception("Empty response from AI"))
          }

          Result.success(AgentTurn(text.toString(), toolCalls.finish()))
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  private suspend fun callAnthropicAPI(apiKey: String, prompt: String): String {
    android.util.Log.d("Anthropic", "Starting API call to Anthropic")
    
    val url = URL("https://api.anthropic.com/v1/messages")
    val connection = url.openConnection() as HttpURLConnection
    
    try {
      connection.runCancellable {
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("x-api-key", apiKey)
        connection.setRequestProperty("anthropic-version", "2023-06-01")
        connection.doOutput = true
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
      
        val messages = JSONArray()
        val userMessage = JSONObject()
        userMessage.put("role", "user")
        userMessage.put("content", prompt)
        messages.put(userMessage)
      
        val requestBody = JSONObject()
        requestBody.put("model", selectedModel)
        requestBody.put("max_tokens", 4096)
        requestBody.put("system", writingRules.useThis())
        requestBody.put("messages", messages)
      
        android.util.Log.d("Anthropic", "Request body: ${requestBody.toString()}")
      
        connection.outputStream.use { os ->
          os.write(requestBody.toString().toByteArray())
        }
      
        val responseCode = connection.responseCode
        android.util.Log.d("Anthropic", "Response code: $responseCode")
      
        if (responseCode != HttpURLConnection.HTTP_OK) {
          val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
          android.util.Log.e("Anthropic", "Error response: $errorStream")
        
          try {
            val errorJson = JSONObject(errorStream)
            val errorObj = errorJson.optJSONObject("error")
            val errorMessage = errorObj?.optString("message") ?: errorStream
            val errorType = errorObj?.optString("type") ?: ""
          
            android.util.Log.e("Anthropic", "Error type: $errorType, message: $errorMessage")
          
            when {
              responseCode == 429 || errorType.contains("rate_limit") -> 
                throw RateLimitException("Anthropic rate limit exceeded: $errorMessage")
              errorType.contains("insufficient_quota") || errorMessage.contains("quota") -> 
                throw QuotaExceededException("Anthropic quota exceeded: $errorMessage")
              errorType.contains("authentication") || responseCode == 401 -> 
                throw InvalidApiKeyException("Invalid Anthropic API key: $errorMessage")
              else -> 
                throw Exception("Anthropic API error ($responseCode) - Type: $errorType, Message: $errorMessage")
            }
          } catch (e: RateLimitException) {
            throw e
          } catch (e: QuotaExceededException) {
            throw e
          } catch (e: InvalidApiKeyException) {
            throw e
          } catch (e: Exception) {
            throw Exception("Anthropic API error ($responseCode): $errorStream")
          }
        }
      
        val responseBody = connection.inputStream.bufferedReader().readText()
        android.util.Log.d("Anthropic", "Success response received, length: ${responseBody.length}")
      
        val jsonResponse = JSONObject(responseBody)
        val content = jsonResponse.getJSONArray("content")
      
        if (content.length() > 0) {
          val firstContent = content.getJSONObject(0)
          return firstContent.getString("text")
        }
      
        throw Exception("No response from Anthropic API")
      }
    } catch (e: RateLimitException) {
      android.util.Log.e("Anthropic", "Rate limit exception", e)
      throw e
    } catch (e: QuotaExceededException) {
      android.util.Log.e("Anthropic", "Quota exceeded exception", e)
      throw e
    } catch (e: InvalidApiKeyException) {
      android.util.Log.e("Anthropic", "Invalid API key exception", e)
      throw e
    } catch (e: java.net.SocketTimeoutException) {
      android.util.Log.e("Anthropic", "Timeout exception", e)
      throw Exception("Anthropic request timeout: ${e.message}")
    } catch (e: java.net.UnknownHostException) {
      android.util.Log.e("Anthropic", "Network exception", e)
      throw Exception("Network error - cannot reach Anthropic: ${e.message}")
    } catch (e: Exception) {
      android.util.Log.e("Anthropic", "General exception", e)
      throw e
    } finally {
      connection.disconnect()
    }
  }

  private fun buildToolRequestBody(
      messages: List<AgentMessage>,
      tools: List<AgentToolSpec>,
  ): JSONObject {
    val requestBody = JSONObject()
    requestBody.put("model", selectedModel)
    requestBody.put("max_tokens", 4096)
    requestBody.put("stream", true)

    messages.filterIsInstance<AgentMessage.System>().firstOrNull()?.let { system ->
      requestBody.put("system", system.content)
    }

    requestBody.put("messages", anthropicMessages(messages))
    requestBody.put("tools", anthropicTools(tools))

    return requestBody
  }

  private fun anthropicMessages(messages: List<AgentMessage>): JSONArray {
    val result = JSONArray()
    var index = 0
    while (index < messages.size) {
      when (val message = messages[index]) {
        is AgentMessage.System -> index++
        is AgentMessage.User -> {
          result.put(JSONObject().put("role", "user").put("content", message.content))
          index++
        }
        is AgentMessage.Assistant -> {
          result.put(assistantMessage(message))
          index++
        }
        is AgentMessage.Tool -> {
          val results = mutableListOf<AgentMessage.Tool>()
          while (index < messages.size && messages[index] is AgentMessage.Tool) {
            results.add(messages[index] as AgentMessage.Tool)
            index++
          }
          result.put(toolResultsMessage(results))
        }
      }
    }
    return result
  }

  private fun assistantMessage(message: AgentMessage.Assistant): JSONObject {
    val blocks = JSONArray()
    if (message.content.isNotBlank()) {
      blocks.put(JSONObject().put("type", "text").put("text", message.content))
    }
    message.toolCalls.forEach { call ->
      blocks.put(
          JSONObject()
              .put("type", "tool_use")
              .put("id", call.id)
              .put("name", call.name)
              .put("input", toolInput(call))
      )
    }
    return JSONObject().put("role", "assistant")
        .put("content", if (blocks.length() > 0) blocks else message.content)
  }

  private fun toolResultsMessage(results: List<AgentMessage.Tool>): JSONObject {
    val blocks = JSONArray()
    results.forEach { result ->
      val block = JSONObject()
          .put("type", "tool_result")
          .put("tool_use_id", result.toolCallId)
          .put("content", result.content)
      if (result.isError) block.put("is_error", true)
      blocks.put(block)
    }
    return JSONObject().put("role", "user").put("content", blocks)
  }

  /** The model sends the arguments as a JSON string; the Messages API expects them as an object. */
  private fun toolInput(call: AgentToolCall): JSONObject =
      try {
        if (call.arguments.isBlank()) JSONObject() else JSONObject(call.arguments)
      } catch (e: Exception) {
        JSONObject()
      }

  private fun anthropicTools(tools: List<AgentToolSpec>): JSONArray {
    val result = JSONArray()
    tools.forEach { tool ->
      result.put(
          JSONObject()
              .put("name", tool.name)
              .put("description", tool.description)
              .put("input_schema", tool.parameters)
      )
    }
    return result
  }

  /**
   * Streams a prebuilt tool-aware request, handing each `data:` payload to [onPayload].
   *
   * Anthropic's stream mixes text, thinking and tool_use blocks on one channel; the payloads carry
   * their own `type`, so the `event:` lines are only framing.
   */
  private suspend fun streamToolTurn(
      apiKey: String,
      requestBody: JSONObject,
      onPayload: (JSONObject) -> Unit,
  ) {
    val url = URL("https://api.anthropic.com/v1/messages")
    val connection = url.openConnection() as HttpURLConnection

    try {
      connection.runCancellable {
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("x-api-key", apiKey)
        connection.setRequestProperty("anthropic-version", "2023-06-01")
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.doOutput = true
        connection.connectTimeout = 30000
        // A stream is idle between chunks, so the read timeout applies per chunk rather than to the
        // whole reply; 30s of silence means the stream has stalled.
        connection.readTimeout = 30000

        connection.outputStream.use { os ->
          os.write(requestBody.toString().toByteArray())
        }

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
          throwApiError(responseCode, connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error")
        }

        connection.inputStream.bufferedReader().use { reader ->
          while (true) {
            val rawLine = reader.readLine() ?: break
            if (!rawLine.startsWith("data:")) continue

            val payload = rawLine.removePrefix("data:").trim()
            if (payload.isEmpty()) continue

            val node = try {
              JSONObject(payload)
            } catch (e: Exception) {
              continue
            }
            onPayload(node)
          }
        }
      }
    } catch (e: RateLimitException) {
      throw e
    } catch (e: QuotaExceededException) {
      throw e
    } catch (e: InvalidApiKeyException) {
      throw e
    } catch (e: java.net.SocketTimeoutException) {
      throw Exception("Anthropic request timeout: ${e.message}")
    } catch (e: java.net.UnknownHostException) {
      throw Exception("Network error - cannot reach Anthropic: ${e.message}")
    } finally {
      connection.disconnect()
    }
  }

  private fun throwApiError(responseCode: Int, errorBody: String): Nothing {
    try {
      val errorJson = JSONObject(errorBody)
      val errorObj = errorJson.optJSONObject("error")
      val errorMessage = errorObj?.optString("message") ?: errorBody
      val errorType = errorObj?.optString("type") ?: ""

      when {
        responseCode == 429 || errorType.contains("rate_limit") ->
            throw RateLimitException("Anthropic rate limit exceeded: $errorMessage")
        errorType.contains("insufficient_quota") || errorMessage.contains("quota") ->
            throw QuotaExceededException("Anthropic quota exceeded: $errorMessage")
        errorType.contains("authentication") || responseCode == 401 ->
            throw InvalidApiKeyException("Invalid Anthropic API key: $errorMessage")
        else ->
            throw Exception("Anthropic API error ($responseCode) - Type: $errorType, Message: $errorMessage")
      }
    } catch (e: RateLimitException) {
      throw e
    } catch (e: QuotaExceededException) {
      throw e
    } catch (e: InvalidApiKeyException) {
      throw e
    } catch (e: Exception) {
      throw Exception("Anthropic API error ($responseCode): $errorBody")
    }
  }

  /** Reads a string field that may be absent or explicitly null; both mean "no fragment". */
  private fun stringOf(node: JSONObject?, name: String): String {
    val raw = node?.opt(name) ?: return ""
    return if (raw === JSONObject.NULL) "" else raw.toString()
  }

  override fun writeFile(filePath: String, content: String): FileWriteResult {
    val writer = fileWriter ?: return FileWriteResult.Error("File writer not initialized")
    return writer.writeFile(filePath, content, createBackup = true)
  }


  override fun isInitialized(): Boolean = apiKey != null
}

/**
 * Accumulates the `tool_use` blocks of an Anthropic stream.
 *
 * A block is announced by `content_block_start` with its id and name; the arguments then arrive as
 * `input_json_delta` fragments, keyed by block index, in first-announcement order.
 */
private class ToolUseAccumulator {

  private class Partial {
    var id = ""
    var name = ""
    val arguments = StringBuilder()
  }

  private val order = mutableListOf<Int>()
  private val partials = mutableMapOf<Int, Partial>()

  fun start(index: Int, id: String, name: String) {
    val partial = partials.getOrPut(index) {
      order.add(index)
      Partial()
    }
    if (id.isNotEmpty()) partial.id = id
    if (name.isNotEmpty()) partial.name = name
  }

  fun append(index: Int, fragment: String) {
    if (fragment.isEmpty()) return
    partials.getOrPut(index) {
      order.add(index)
      Partial()
    }.arguments.append(fragment)
  }

  fun hasCalls(): Boolean = order.isNotEmpty()

  fun finish(): List<AgentToolCall> =
      order.mapNotNull { index ->
        val partial = partials[index] ?: return@mapNotNull null
        if (partial.name.isEmpty()) return@mapNotNull null
        AgentToolCall(
            id = partial.id.ifEmpty { CALL_ID_PREFIX + index },
            name = partial.name,
            arguments = partial.arguments.toString()
        )
      }

  private companion object {
    const val CALL_ID_PREFIX = "toolu_"
  }
}
