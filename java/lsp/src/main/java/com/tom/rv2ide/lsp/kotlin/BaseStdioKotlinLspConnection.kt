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
package com.tom.rv2ide.lsp.kotlin

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.models.DiagnosticResult
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reusable stdio JSON-RPC transport base for Kotlin LSP backends.
 *
 * Subclasses only need to provide a started [Process]. Request tracking, message
 * framing, reader threads, JSON parsing and diagnostics dispatch stay shared.
 */
abstract class BaseStdioKotlinLspConnection : KotlinLspConnection {
  companion object {
    private const val BUFFER_SIZE = 32768
    private const val MAX_CONTENT_LENGTH = 10485760
  }

  private val gson = Gson()
  private var process: Process? = null
  private var writer: BufferedWriter? = null
  private var reader: BufferedReader? = null
  private val nextId = AtomicInteger(1)
  private val pendingRequests = ConcurrentHashMap<Int, (JsonObject?) -> Unit>()
  private val notificationHandler = KotlinNotificationHandler()
  private val executorService = Executors.newFixedThreadPool(2)

  protected abstract fun startProcess(classpathProvider: KotlinClasspathProvider): Process?

  protected open fun onProcessStarted(process: Process, classpathProvider: KotlinClasspathProvider) = Unit

  protected open fun onProcessStartFailed(error: Exception) {
    KslLogs.error("Failed to start Kotlin LSP backend process", error)
  }

  protected open fun logPrefix(): String = "Kotlin LSP backend"

  protected fun currentProcess(): Process? = process

  override fun setDiagnosticsCallback(callback: (DiagnosticResult) -> Unit) {
    notificationHandler.setDiagnosticsCallback(callback)
  }

  override fun startServer(classpathProvider: KotlinClasspathProvider) {
    if (process?.isAlive == true) {
      KslLogs.debugThrottled("kls:already-running", 3000L, "{} already running", logPrefix())
      return
    }

    try {
      val startedProcess = startProcess(classpathProvider) ?: return
      process = startedProcess
      writer =
          BufferedWriter(
              OutputStreamWriter(startedProcess.outputStream, StandardCharsets.UTF_8),
              BUFFER_SIZE,
          )
      reader =
          BufferedReader(
              InputStreamReader(startedProcess.inputStream, StandardCharsets.UTF_8),
              BUFFER_SIZE,
          )

      startReaderThread()
      startErrorReaderThread(startedProcess)
      onProcessStarted(startedProcess, classpathProvider)
    } catch (e: Exception) {
      onProcessStartFailed(e)
    }
  }

  override fun sendRequest(method: String, params: JsonObject, callback: (JsonObject?) -> Unit) {
    val id = nextId.getAndIncrement()
    pendingRequests[id] = callback

    val payload =
        JsonObject().apply {
          addProperty("jsonrpc", "2.0")
          addProperty("id", id)
          addProperty("method", method)
          add("params", params)
        }

    KslLogs.debugThrottled("kls:request:$method", 1500L, "Sending request: {}", method)
    sendMessage(payload)
  }

  override fun sendNotification(method: String, params: JsonObject) {
    val payload =
        JsonObject().apply {
          addProperty("jsonrpc", "2.0")
          addProperty("method", method)
          add("params", params)
        }

    KslLogs.debugThrottled("kls:notification:$method", 1500L, "Sending notification: {}", method)
    sendMessage(payload)
  }

  override fun sendNotificationOrThrow(method: String, params: JsonObject) {
    val payload =
        JsonObject().apply {
          addProperty("jsonrpc", "2.0")
          addProperty("method", method)
          add("params", params)
        }

    KslLogs.debugThrottled("kls:notification-throw:$method", 1500L, "Sending notification: {}", method)
    sendMessageOrThrow(payload)
  }

  protected fun sendMessage(payload: JsonObject) {
    try {
      sendMessageOrThrow(payload)
    } catch (e: Exception) {
      KslLogs.error("Failed to send message", e)
    }
  }

  protected fun sendMessageOrThrow(payload: JsonObject) {
    val data = gson.toJson(payload)
    val w = writer ?: throw IllegalStateException("Cannot send message: writer is null")

    synchronized(w) {
      try {
        val contentBytes = data.toByteArray(StandardCharsets.UTF_8)
        w.write("Content-Length: ${contentBytes.size}\r\n\r\n")
        w.write(data)
        w.flush()
      } catch (e: Exception) {
        KslLogs.error("Failed to send message", e)
      }
    }
  }

  private fun startReaderThread() {
    val r = reader ?: return
    Thread(
            {
              try {
                while (true) {
                  var contentLength = -1
                  while (true) {
                    val line = r.readLine() ?: return@Thread
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                      contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
                    }
                  }

                  if (contentLength <= 0 || contentLength > MAX_CONTENT_LENGTH) {
                    KslLogs.warn("Invalid content length: {}", contentLength)
                    continue
                  }

                  val buffer = CharArray(contentLength)
                  var totalRead = 0
                  while (totalRead < contentLength) {
                    val read = r.read(buffer, totalRead, contentLength - totalRead)
                    if (read < 0) break
                    totalRead += read
                  }

                  val json = String(buffer, 0, totalRead)
                  executorService.submit { handleMessage(json) }
                }
              } catch (e: Exception) {
                KslLogs.error("Error in reader thread", e)
              }
            },
            "kls-jsonrpc-reader",
        )
        .apply { priority = Thread.MAX_PRIORITY }
        .start()
  }

  private fun startErrorReaderThread(startedProcess: Process) {
    val errorReader =
        BufferedReader(
            InputStreamReader(startedProcess.errorStream, StandardCharsets.UTF_8),
            BUFFER_SIZE,
        )
    Thread(
            {
              try {
                var line: String?
                while (errorReader.readLine().also { line = it } != null) {
                  val stderrLine = line ?: continue
                  if (stderrLine.isBlank()) continue
                  KslLogs.warn(
                      "{} stderr: {}",
                      logPrefix(),
                      stderrLine.take(500),
                  )
                }
              } catch (e: Exception) {
                KslLogs.error("Error in error reader thread", e)
              }
            },
            "kls-error-reader",
        )
        .start()
  }

  private fun handleMessage(json: String) {
    try {
      val obj = gson.fromJson(json, JsonObject::class.java)

      if (obj.has("id")) {
        val id = obj.get("id").asInt
        val callback = pendingRequests.remove(id)

        if (callback == null) {
          KslLogs.warn("No callback found for request ID: {}", id)
          return
        }

        if (obj.has("error")) {
          val error = obj.getAsJsonObject("error")
          val errorMsg = error.get("message")?.asString ?: "Unknown error"
          val errorCode = error.get("code")?.asInt ?: -1
          KslLogs.error("LSP error response for request {}: [{}] {}", id, errorCode, errorMsg)
          callback.invoke(null)
        } else if (obj.has("result")) {
          val result = obj.get("result")
          when {
            result.isJsonNull -> callback.invoke(null)
            result.isJsonArray -> callback.invoke(JsonObject().apply { add("result", result) })
            result.isJsonObject -> callback.invoke(result.asJsonObject)
            result.isJsonPrimitive -> callback.invoke(JsonObject().apply { add("result", result) })
            else -> callback.invoke(null)
          }
        } else {
          KslLogs.warn("Request {} has neither result nor error", id)
          callback.invoke(null)
        }
      } else if (obj.has("method")) {
        notificationHandler.handle(obj)
      } else {
        KslLogs.warn("Message has neither id nor method: {}", json.take(200))
      }
    } catch (e: Exception) {
      KslLogs.error("Error handling message: {}", json.take(200), e)
    }
  }

  override fun shutdown() {
    try {
      sendRequest("shutdown", JsonObject()) {}
      sendNotification("exit", JsonObject())
      Thread.sleep(500)
    } catch (e: Exception) {
      KslLogs.error("Error during shutdown", e)
    }

    executorService.shutdown()

    try {
      writer?.close()
    } catch (_: Exception) {}
    try {
      reader?.close()
    } catch (_: Exception) {}
    try {
      process?.destroy()
    } catch (_: Exception) {}

    process = null
    pendingRequests.clear()
  }
}
