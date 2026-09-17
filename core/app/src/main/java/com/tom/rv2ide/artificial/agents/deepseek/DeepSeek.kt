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

package com.tom.rv2ide.artificial.agents.deepseek

import android.content.Context
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.AgentStreamEvent
import com.tom.rv2ide.artificial.agents.AgentStreamParser
import com.tom.rv2ide.artificial.agents.ModificationAttempt
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

class DeepSeek : AIAgent {

  private var apiKey: String? = null
  private val writingRules = WritingRules.Instructions()
  private var projectTreeResult: ProjectTreeResult? = null
  private var fileWriter: AIFileWriter? = null
  private val conversationHistory = mutableListOf<ConversationMessage>()
  private val modificationHistory = mutableListOf<ModificationAttempt>()
  private var currentAttemptCount = 0
  private val maxRetryAttempts = 3
  private var agents: Agents? = null
  private var selectedModel: String = ModelRepository.getDefaultModel(PROVIDER_ID)
  override val providerId = PROVIDER_ID
  override val providerName = ModelSources.providerName(PROVIDER_ID)

  companion object {
      /** Id shared by the registry, the catalogue and the persisted selection. */
      const val PROVIDER_ID = "deepseek"

      fun registerAgent() {
          AIAgentRegistry.register(PROVIDER_ID, object : AIAgentRegistry.AgentFactory {
              override fun create(context: Context): AIAgent {
                  return DeepSeek()
              }
              
              override fun hasValidApiKey(): Boolean {
                  val apiKey = getApiKey()
                  return apiKey != null && apiKey.isNotBlank() && apiKey.length > 20
              }
              
              override fun getApiKey(): String? {
                  return ApiKey.getDeepseekApiKey().takeIf { it.isNotBlank() }
              }
          })
      }
  }
        
  override fun initialize(apiKey: String, context: Context) {
      try {
          this.apiKey = apiKey
          agents = Agents(context)
          val agentsRef = agents!!
          // Resolved through the catalogue instead of a literal: this fallback is what used to keep
          // pointing at a retired model name.
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
    conversationHistory.clear()
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
                  IllegalStateException("DeepSeek service not initialized")
              )

          val fullPrompt = buildFullPrompt(prompt, context)
          val response = callDeepSeekAPI(key, fullPrompt)

          if (response.isBlank()) {
            return@withContext Result.failure(Exception("Empty response from AI"))
          }

          recordTurn(prompt, response)
          Result.success(response)
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  override suspend fun generateCodeStreaming(
      prompt: String,
      context: String?,
      language: String,
      projectStructure: String?,
      onEvent: (AgentStreamEvent) -> Unit,
  ): Result<String> =
      withContext(Dispatchers.IO) {
        try {
          val key = apiKey
              ?: return@withContext Result.failure(
                  IllegalStateException("DeepSeek service not initialized")
              )

          val fullPrompt = buildFullPrompt(prompt, context)
          val parser = AgentStreamParser()
          val streamed = StringBuilder()

          streamDeepSeekAPI(key, fullPrompt) { delta ->
            streamed.append(delta)
            parser.accept(delta, onEvent)
          }

          parser.finish(onEvent)
          val response = streamed.toString()

          if (response.isBlank()) {
            return@withContext Result.failure(Exception("Empty response from AI"))
          }

          recordTurn(prompt, response)
          Result.success(response)
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  /**
   * The conversation history keeps the raw reply, file bodies included: the next request replays it,
   * and the protocol is what tells the model where the blocks are.
   */
  private fun recordTurn(prompt: String, response: String) {
    conversationHistory.add(ConversationMessage("user", prompt))
    conversationHistory.add(ConversationMessage("assistant", response))

    if (conversationHistory.size > 20) {
      conversationHistory.removeAt(0)
      conversationHistory.removeAt(0)
    }
  }

  private fun buildFullPrompt(prompt: String, context: String?): String {
    val fileContents = readRelevantFiles()
    val needsCorrection = isUserRequestingCorrection(prompt)

    return buildString {
      append("=== PROJECT STRUCTURE (THESE ARE THE EXACT PATHS YOU MUST USE) ===\n")
      if (projectTreeResult != null) {
        append(projectTreeResult!!.tree)
        append("\n\n")
        append("CRITICAL: Use ONLY the paths shown above. Do NOT make up fake paths like '/storage/emulated/0/project' or 'com.example.yourproject'.\n")
        append("CRITICAL: Look at the actual paths above and use those EXACT paths.\n\n")
      }

      if (fileContents.isNotEmpty()) {
        append("=== CURRENT FILES CONTENT ===\n")
        fileContents.forEach { (path, content) ->
          append("FILE: $path\n")
          append("CONTENT:\n")
          append(content)
          append("\n\n")
        }
      }

      if (context != null) {
        append("=== ADDITIONAL CONTEXT ===\n")
        append(context)
        append("\n\n")
      }

      if (conversationHistory.isNotEmpty()) {
        append("=== CONVERSATION HISTORY ===\n")
        conversationHistory.forEach { msg ->
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
  }

  private fun buildMessages(fullPrompt: String, stream: Boolean): JSONObject {
    val messages = JSONArray()

    val systemMessage = JSONObject()
    systemMessage.put("role", "system")
    systemMessage.put("content", writingRules.useThis())
    messages.put(systemMessage)

    val userMessage = JSONObject()
    userMessage.put("role", "user")
    userMessage.put("content", fullPrompt)
    messages.put(userMessage)

    val requestBody = JSONObject()
    requestBody.put("model", selectedModel)
    requestBody.put("messages", messages)
    requestBody.put("temperature", 0.7)
    requestBody.put("max_tokens", 4096)
    if (stream) requestBody.put("stream", true)

    return requestBody
  }

  /**
   * Reads the reply as it is produced, handing each content fragment to [onDelta].
   *
   * OpenAI-compatible SSE: one `data: {...}` line per chunk, each carrying
   * `choices[0].delta.content`, terminated by `data: [DONE]`.
   */
  private fun streamDeepSeekAPI(apiKey: String, prompt: String, onDelta: (String) -> Unit) {
    val connection = openConnection(apiKey)

    try {
      val requestBody = buildMessages(prompt, stream = true)
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
          if (payload.isEmpty() || payload == "[DONE]") continue

          val delta = try {
            JSONObject(payload)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
                ?.optString("content")
                .orEmpty()
          } catch (e: Exception) {
            ""
          }

          if (delta.isNotEmpty()) onDelta(delta)
        }
      }
    } catch (e: RateLimitException) {
      throw e
    } catch (e: QuotaExceededException) {
      throw e
    } catch (e: InvalidApiKeyException) {
      throw e
    } catch (e: java.net.SocketTimeoutException) {
      throw Exception("DeepSeek request timeout: ${e.message}")
    } catch (e: java.net.UnknownHostException) {
      throw Exception("Network error - cannot reach DeepSeek: ${e.message}")
    } finally {
      connection.disconnect()
    }
  }

  private fun openConnection(apiKey: String): HttpURLConnection {
    val connection = URL("https://api.deepseek.com/chat/completions").openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.setRequestProperty("Content-Type", "application/json")
    connection.setRequestProperty("Authorization", "Bearer $apiKey")
    connection.setRequestProperty("Accept", "text/event-stream")
    connection.doOutput = true
    connection.connectTimeout = 30000
    // A stream is idle between chunks, so the read timeout applies per chunk rather than to the
    // whole reply; 30s of silence means the stream has stalled.
    connection.readTimeout = 30000
    return connection
  }

  private fun throwApiError(responseCode: Int, errorBody: String): Nothing {
    try {
      val errorJson = JSONObject(errorBody)
      val errorObj = errorJson.optJSONObject("error")
      val errorMessage = errorObj?.optString("message") ?: errorBody
      val errorType = errorObj?.optString("type") ?: ""
      val errorCode = errorObj?.optString("code") ?: ""

      when {
        responseCode == 429 || errorType.contains("rate_limit") || errorCode.contains("rate_limit") ->
            throw RateLimitException("DeepSeek rate limit exceeded: $errorMessage")
        errorType.contains("insufficient_quota") || errorMessage.contains("quota") || errorMessage.contains("billing") ->
            throw QuotaExceededException("DeepSeek quota exceeded: $errorMessage")
        errorType.contains("invalid_api_key") || errorCode.contains("invalid_api_key") ->
            throw InvalidApiKeyException("Invalid DeepSeek API key: $errorMessage")
        responseCode == 401 ->
            throw InvalidApiKeyException("DeepSeek authentication failed: $errorMessage")
        else ->
            throw Exception("DeepSeek API error ($responseCode) - Type: $errorType, Code: $errorCode, Message: $errorMessage")
      }
    } catch (e: RateLimitException) {
      throw e
    } catch (e: QuotaExceededException) {
      throw e
    } catch (e: InvalidApiKeyException) {
      throw e
    } catch (e: Exception) {
      throw Exception("DeepSeek API error ($responseCode): $errorBody")
    }
  }

  private fun callDeepSeekAPI(apiKey: String, prompt: String): String {
    android.util.Log.d("DeepSeek", "Starting API call to DeepSeek")

    val connection = openConnection(apiKey)

    try {
      val requestBody = buildMessages(prompt, stream = false)

      android.util.Log.d("DeepSeek", "Request body: ${requestBody.toString()}")

      connection.outputStream.use { os ->
        os.write(requestBody.toString().toByteArray())
      }

      val responseCode = connection.responseCode
      android.util.Log.d("DeepSeek", "Response code: $responseCode")

      if (responseCode != HttpURLConnection.HTTP_OK) {
        val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
        android.util.Log.e("DeepSeek", "Error response: $errorStream")
        throwApiError(responseCode, errorStream)
      }

      val responseBody = connection.inputStream.bufferedReader().readText()
      android.util.Log.d("DeepSeek", "Success response received, length: ${responseBody.length}")

      val jsonResponse = JSONObject(responseBody)

      val choices = jsonResponse.getJSONArray("choices")
      if (choices.length() > 0) {
        val firstChoice = choices.getJSONObject(0)
        val message = firstChoice.getJSONObject("message")
        return message.getString("content")
      }

      throw Exception("No response from DeepSeek API")
    } catch (e: RateLimitException) {
      android.util.Log.e("DeepSeek", "Rate limit exception", e)
      throw e
    } catch (e: QuotaExceededException) {
      android.util.Log.e("DeepSeek", "Quota exceeded exception", e)
      throw e
    } catch (e: InvalidApiKeyException) {
      android.util.Log.e("DeepSeek", "Invalid API key exception", e)
      throw e
    } catch (e: java.net.SocketTimeoutException) {
      android.util.Log.e("DeepSeek", "Timeout exception", e)
      throw Exception("DeepSeek request timeout: ${e.message}")
    } catch (e: java.net.UnknownHostException) {
      android.util.Log.e("DeepSeek", "Network exception", e)
      throw Exception("Network error - cannot reach DeepSeek: ${e.message}")
    } catch (e: Exception) {
      android.util.Log.e("DeepSeek", "General exception", e)
      throw e
    } finally {
      connection.disconnect()
    }
  }

  private fun readRelevantFiles(): Map<String, String> {
    val filesContent = mutableMapOf<String, String>()
    val tree = projectTreeResult?.tree ?: return filesContent
    
    val filePaths = tree.lines().filter { it.isNotBlank() }
    
    filePaths.forEach { filePath ->
      val trimmedPath = filePath.trim()
      val file = File(trimmedPath)
      
      if (file.isFile && 
          (trimmedPath.endsWith(".kt") || 
           trimmedPath.endsWith(".java") ||
           trimmedPath.endsWith(".xml") ||
           trimmedPath.endsWith(".gradle") ||
           trimmedPath.endsWith(".gradle.kts")) &&
          !trimmedPath.contains("/build/") && 
          !trimmedPath.contains("/.gradle/")) {
        try {
          val content = file.readText()
          filesContent[trimmedPath] = content
        } catch (e: Exception) {
        }
      }
    }
    
    return filesContent
  }

  fun readFile(filePath: String): String? {
    return try {
      if (File(filePath).exists()) {
        File(filePath).readText()
      } else {
        projectTreeResult?.readFileContent(File(filePath).name)
      }
    } catch (e: Exception) {
      null
    }
  }

  override fun writeFile(filePath: String, content: String): FileWriteResult {
    val writer = fileWriter ?: return FileWriteResult.Error("File writer not initialized")
    return writer.writeFile(filePath, content, createBackup = true)
  }


  override fun isInitialized(): Boolean = apiKey != null
}


data class ConversationMessage(
    val role: String,
    val content: String
)
