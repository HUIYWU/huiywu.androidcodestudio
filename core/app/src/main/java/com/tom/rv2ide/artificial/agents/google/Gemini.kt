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

package com.tom.rv2ide.artificial.agents.google

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.AgentHistory
import com.tom.rv2ide.artificial.agents.ModificationAttempt
import com.tom.rv2ide.artificial.catalog.ModelSources
import com.tom.rv2ide.artificial.secrets.ApiKey
import com.tom.rv2ide.artificial.rules.WritingRules
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.tom.rv2ide.artificial.agents.Agents

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

class Gemini : AIAgent {

  private var generativeModel: GenerativeModel? = null
  private val writingRules = WritingRules.Instructions()
  private var projectTreeResult: ProjectTreeResult? = null
  private var fileWriter: AIFileWriter? = null
  override val history = AgentHistory()
  private val modificationHistory = mutableListOf<ModificationAttempt>()
  private var currentAttemptCount = 0
  private val maxRetryAttempts = 3
  private var agents: Agents? = null
  override val providerId = PROVIDER_ID
  override val providerName = ModelSources.providerName(PROVIDER_ID)

  companion object {
      /** Id shared by the registry, the catalogue and the persisted selection. */
      const val PROVIDER_ID = "gemini"

      fun registerAgent() {
          AIAgentRegistry.register(PROVIDER_ID, object : AIAgentRegistry.AgentFactory {
              override fun create(context: Context): AIAgent {
                  return Gemini()
              }
              
              override fun hasValidApiKey(): Boolean {
                  val key = ApiKey.getApiKey()
                  android.util.Log.d("Gemini", "hasValidApiKey check: ${key != null && key.isNotEmpty()}, key length: ${key?.length ?: 0}")
                  return key != null && key.isNotEmpty()
              }
              
              override fun getApiKey(): String? {
                  val key = ApiKey.getApiKey()
                  android.util.Log.d("Gemini", "getApiKey called, returning key of length: ${key?.length ?: 0}")
                  return key
              }
          })
      }
  }
        
  override fun initialize(apiKey: String, context: Context) {
      try {
          agents = Agents(context)
          val agentsRef = agents!!
          // Resolved through the catalogue instead of a literal: a stored name the provider no
          // longer offers is replaced by its default, which is what the copies of these fallbacks
          // used to disagree about.
          val selectedModel = agentsRef.resolveModel(PROVIDER_ID)

          generativeModel = GenerativeModel(
              modelName = selectedModel,
              apiKey = apiKey,
              systemInstruction = content { text(writingRules.useThis()) }
          )
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
          val model =
              generativeModel
                  ?: return@withContext Result.failure(
                      IllegalStateException("Gemini AI service not initialized")
                  )

          val fileContents = readRelevantFiles()
          val needsCorrection = isUserRequestingCorrection(prompt)

          val fullPrompt = buildString {
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

          val response = try {
            model.generateContent(content { text(fullPrompt) })
          } catch (e: Exception) {
            // Parse Gemini-specific errors
            val errorMessage = e.message ?: ""
            when {
              errorMessage.contains("RESOURCE_EXHAUSTED") || 
              errorMessage.contains("quota") ||
              errorMessage.contains("429") -> 
                throw com.tom.rv2ide.artificial.exceptions.QuotaExceededException(
                  "Gemini API quota exceeded. Switching to another provider..."
                )
              errorMessage.contains("RATE_LIMIT") ||
              errorMessage.contains("rate limit") -> 
                throw com.tom.rv2ide.artificial.exceptions.RateLimitException(
                  "Gemini rate limit exceeded. Switching to another provider..."
                )
              errorMessage.contains("INVALID_ARGUMENT") ||
              errorMessage.contains("API key") -> 
                throw com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException(
                  "Invalid Gemini API key. Please check your configuration."
                )
              else -> throw e
            }
          }

          val generatedResponse = response.text ?: ""
          if (generatedResponse.isBlank()) {
            return@withContext Result.failure(Exception("Empty response from AI"))
          }

          Result.success(generatedResponse)
        } catch (e: com.tom.rv2ide.artificial.exceptions.RateLimitException) {
          Result.failure(e)
        } catch (e: com.tom.rv2ide.artificial.exceptions.QuotaExceededException) {
          Result.failure(e)
        } catch (e: com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException) {
          Result.failure(e)
        } catch (e: Exception) {
          Result.failure(e)
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
          // Skip files that can't be read
        }
      }
    }
    
    return filesContent
  }

  override fun writeFile(filePath: String, content: String): FileWriteResult {
    val writer = fileWriter ?: return FileWriteResult.Error("File writer not initialized")
    return writer.writeFile(filePath, content, createBackup = true)
  }


  override fun isInitialized(): Boolean = generativeModel != null
}