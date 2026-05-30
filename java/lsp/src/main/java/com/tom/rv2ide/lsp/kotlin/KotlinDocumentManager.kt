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

import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class KotlinDocumentManager(
    private val processManager: KotlinLspConnection,
    private val isServerReady: () -> Boolean = { true },
) {

  private data class PendingOpenDocument(
      val file: Path,
      val uri: String,
      val text: String,
      val queuedAtMs: Long,
  )


  companion object {
    private val log = LoggerFactory.getLogger(KotlinDocumentManager::class.java)
  }

  private val openedDocuments = ConcurrentHashMap.newKeySet<String>()
  private val documentVersions = ConcurrentHashMap<String, Int>()
  private val pendingOpenDocuments = ConcurrentHashMap<String, PendingOpenDocument>()

  fun ensureDocumentOpen(file: Path, content: String? = null) {
    val uri = file.toUri().toString()
    if (openedDocuments.contains(uri)) {
      if (getDocumentVersion(uri) <= 0) {
        setDocumentVersion(uri, 1)
      }
      return
    }

    val text =
        content
            ?: try {
              file.toFile().readText()
            } catch (e: Exception) {
              KslLogs.error("Failed to read file: {}", file, e)
              return
            }

    if (!isServerReady()) {
      pendingOpenDocuments[uri] =
          PendingOpenDocument(
              file = file,
              uri = uri,
              text = text,
              queuedAtMs = android.os.SystemClock.elapsedRealtime(),
          )
      KslLogs.debug("Queue Kotlin didOpen until server is ready: {}", uri)
      return
    }

    openDocumentNow(file, uri, text)
  }

  fun flushPendingOpens() {
    if (!isServerReady()) {
      return
    }

    val pending = pendingOpenDocuments.values.sortedBy { it.queuedAtMs }
    if (pending.isEmpty()) {
      return
    }

    pending.forEach { pendingOpen ->
      if (openedDocuments.contains(pendingOpen.uri)) {
        pendingOpenDocuments.remove(pendingOpen.uri)
        return@forEach
      }

      if (openDocumentNow(pendingOpen.file, pendingOpen.uri, pendingOpen.text)) {
        pendingOpenDocuments.remove(pendingOpen.uri)
      }
    }
  }

  private fun openDocumentNow(file: Path, uri: String, text: String): Boolean {
    val version = getDocumentVersion(uri).coerceAtLeast(0) + 1
    setDocumentVersion(uri, version)

    val params =
        JsonObject().apply {
          add(
              "textDocument",
              JsonObject().apply {
                addProperty("uri", uri)
                addProperty("languageId", "kotlin")
                addProperty("version", version)
                addProperty("text", text)
              },
          )
        }

    return try {
      KslLogs.info("Sending didOpen notification for: {}", uri)
      processManager.sendNotificationOrThrow("textDocument/didOpen", params)
      openedDocuments.add(uri)
      pendingOpenDocuments.remove(uri)

      // Some servers require an explicit open-before-lint; trigger an initial lint after open.
      android.os
          .Handler(android.os.Looper.getMainLooper())
          .postDelayed(
              {
                KslLogs.info("Sending didSave notification for: {}", uri)
                notifyDocumentSave(file)
              },
              120,
          )
      true
    } catch (e: Exception) {
      KslLogs.warn("didOpen failed, document remains unopened: {}", uri, e)
      openedDocuments.remove(uri)
      false
    }
  }

  fun notifyDocumentChange(file: Path, newText: String, version: Int) {
    val uri = file.toUri().toString()

    if (!openedDocuments.contains(uri)) {
      KslLogs.warn("Document not opened, opening it first: {}", uri)
      ensureDocumentOpen(file, newText)
      return
    }

    KslLogs.debug("Notifying document change: {} (version: {})", uri, version)

    val params =
        JsonObject().apply {
          add(
              "textDocument",
              JsonObject().apply {
                addProperty("uri", uri)
                addProperty("version", version)
              },
          )
          add(
              "contentChanges",
              com.google.gson.JsonArray().apply {
                add(JsonObject().apply { addProperty("text", newText) })
              },
          )
        }

    processManager.sendNotification("textDocument/didChange", params)
  }

  fun notifyDocumentSave(file: Path, text: String? = null) {
    val uri = file.toUri().toString()
    if (!openedDocuments.contains(uri)) {
      KslLogs.debug("Skip didSave for unopened document: {}", uri)
      return
    }

    val currentText =
        text
            ?: try {
              file.toFile().readText()
            } catch (e: Exception) {
              KslLogs.warn("Failed to read file for didSave, sending save without text: {}", uri, e)
              null
            }

    val params =
        JsonObject().apply {
          add(
              "textDocument",
              JsonObject().apply {
                addProperty("uri", uri)
                if (currentText != null) {
                  addProperty("text", currentText)
                }
              },
          )
        }
    KslLogs.debug("Sending didSave notification for: {}", uri)
    processManager.sendNotification("textDocument/didSave", params)
  }

  fun closeDocument(file: Path) {
    val uri = file.toUri().toString()
    if (openedDocuments.remove(uri)) {
      val params =
          JsonObject().apply { add("textDocument", JsonObject().apply { addProperty("uri", uri) }) }
      processManager.sendNotification("textDocument/didClose", params)
    }
  }

  fun isDocumentOpen(uri: String): Boolean = openedDocuments.contains(uri)

  fun getDocumentVersion(uri: String): Int = documentVersions.getOrDefault(uri, 0)

  fun setDocumentVersion(uri: String, version: Int) {
    documentVersions[uri] = version
  }

  fun clear() {
    openedDocuments.clear()
    documentVersions.clear()
  }
}
