/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.projects

import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.eventbus.events.file.FileDeletionEvent
import com.tom.rv2ide.eventbus.events.file.FileRenameEvent
import com.tom.rv2ide.progress.ProgressManager
import com.tom.rv2ide.projects.models.ActiveDocument
import com.tom.rv2ide.projects.models.ActiveDocumentSnapshot
import java.io.BufferedReader
import java.io.InputStream
import java.net.URI
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

/**
 * Manages active documents.
 *
 * @author Akash Yadav
 */
object FileManager {

  private val log = LoggerFactory.getLogger(FileManager::class.java)
  private val activeDocuments = ConcurrentHashMap<Path, ActiveDocument>()
  private val documentRevision = AtomicLong()

  fun isActive(uri: URI): Boolean {
    return isActive(Paths.get(uri))
  }

  fun isActive(file: Path): Boolean {
    return this.activeDocuments.containsKey(file.normalize())
  }

  fun getActiveDocument(file: Path): ActiveDocument? {
    return this.activeDocuments[file.normalize()]
  }

  fun getActiveDocumentSnapshot(file: Path): ActiveDocumentSnapshot? {
    return getActiveDocument(file)?.snapshot()
  }

  fun getActiveDocumentSnapshots(files: Collection<Path>): Map<Path, ActiveDocumentSnapshot> {
    return files.mapNotNull { file ->
      getActiveDocumentSnapshot(file)?.let { snapshot -> snapshot.file.normalize() to snapshot }
    }.toMap()
  }

  fun getActiveDocumentCount(): Int {
    return this.activeDocuments.size
  }

  /** Returns a stable snapshot of active paths without exposing mutable document state. */
  fun getActiveDocumentFiles(): Set<Path> {
    return this.activeDocuments.keys.toSet()
  }

  fun getDocumentContents(file: Path): String {
    val document = getActiveDocumentSnapshot(file)
    if (document != null) {
      return document.content
    }

    return getFileContents(file)
  }

  fun getLastModified(file: Path): Instant {
    val document = getActiveDocumentSnapshot(file)
    if (document != null) {
      return document.modified
    }

    return getLastModifiedFromDisk(file)
  }

  fun getReader(file: Path): BufferedReader {
    val document = getActiveDocument(file)
    if (document != null) {
      return document.reader()
    }

    return createFileReader(file)
  }

  fun getInputStream(file: Path): InputStream {
    val document = getActiveDocument(file)
    if (document != null) {
      return document.inputStream()
    }

    return createFileInputStream(file)
  }
  fun onDocumentOpen(event: DocumentOpenEvent) {
    activeDocuments[event.openedFile.normalize()] = createDocument(event)
  }


  fun onDocumentContentChange(event: DocumentChangeEvent) {
    val document = activeDocuments[event.changedFile.normalize()]

    if (document == null) {
      // create document if not already created
      // this should not happen under normal circumstances
      activeDocuments[event.changedFile.normalize()] = createDocument(event)
      log.warn("Document change event received before open event for file {}", event.changedFile)
      return
    }

    val current = document.snapshot()
    document.update(
        version = event.version,
        modified = Instant.now(),
        content = event.newText ?: current.content,
        revision = documentRevision.incrementAndGet(),
    )
  }

  fun onDocumentClose(event: DocumentCloseEvent) {
    activeDocuments.remove(event.closedFile.normalize())
  }

  fun onFileRenamed(event: FileRenameEvent) {
    val document = activeDocuments.remove(event.file.toPath().normalize())
    if (document != null) {
      val snapshot = document.snapshot()
      val newPath = event.newFile.toPath().normalize()
      activeDocuments[newPath] =
          ActiveDocument(
              file = newPath,
              version = snapshot.version,
              modified = Instant.now(),
              content = snapshot.content,
              revision = documentRevision.incrementAndGet(),
          )
    }
  }

  fun onFileDeleted(event: FileDeletionEvent) {
    // If the file was an active document, remove the document cache
    activeDocuments.remove(event.file.toPath().normalize())
  }

  private fun createDocument(event: DocumentOpenEvent): ActiveDocument {
    val initialContent = readOpenEventText(event)
    // Keep the event coherent for subscribers posted after FileManager.onDocumentOpen(). Large-file
    // editor flows may intentionally omit the eager payload, but downstream language services still
    // expect DocumentOpenEvent.text to contain the initial snapshot when available.
    if (event.text.isEmpty() && initialContent.isNotEmpty()) {
      event.text = initialContent
    }
    return ActiveDocument(
        file = event.openedFile.normalize(),
        version = event.version,
        modified = Instant.now(),
        content = initialContent,
        revision = documentRevision.incrementAndGet(),
    )
  }

  private fun readOpenEventText(event: DocumentOpenEvent): String {
    if (event.text.isNotEmpty()) {
      return event.text
    }

    return try {
      if (Files.exists(event.openedFile) && Files.size(event.openedFile) > 0L) {
        getFileContents(event.openedFile)
      } else {
        ""
      }
    } catch (error: Exception) {
      log.warn("Unable to recover opened document contents for {}", event.openedFile, error)
      ""
    }
  }

  private fun createDocument(event: DocumentChangeEvent): ActiveDocument {
    val initialContent = event.newText ?: getFileContents(event.changedFile)
    return ActiveDocument(
        file = event.changedFile.normalize(),
        version = event.version,
        modified = Instant.now(),
        content = initialContent,
        revision = documentRevision.incrementAndGet(),
    )
  }

  private fun createFileReader(file: Path): BufferedReader {
    return try {
      Files.newBufferedReader(file)
    } catch (noFile: java.nio.file.NoSuchFileException) {
      log.warn("No such file", noFile)
      "".reader().buffered()
    } catch (cancelled: CancellationException) {
      "".reader().buffered()
    }
  }

  private fun createFileInputStream(file: Path): InputStream {
    return try {
      Files.newInputStream(file)
    } catch (noFile: java.nio.file.NoSuchFileException) {
      log.warn("No such file", noFile)
      "".byteInputStream()
    } catch (cancelled: CancellationException) {
      "".byteInputStream()
    }
  }

  private fun getLastModifiedFromDisk(file: Path): Instant {
    return Files.getLastModifiedTime(file).toInstant()
  }

  private fun getFileContents(file: Path): String {
    return try {
      ProgressManager.abortIfCancelled()
      FileUtils.readFileToString(file.toFile(), Charset.defaultCharset())
    } catch (noFile: java.nio.file.NoSuchFileException) {
      log.warn("No such file", noFile)
      ""
    } catch (cancelled: CancellationException) {
      ""
    }
  }
}
