/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.projects

import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.eventbus.events.editor.ChangeType
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.models.ActiveDocument
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Test

class FileManagerTest {

  @Test
  fun documentSnapshotsRemainCoherentDuringConcurrentUpdates() {
    val document =
        ActiveDocument(
            file = Files.createTempFile("active-document", ".xml"),
            version = 0,
            modified = Instant.EPOCH,
            content = "content-0",
            revision = 0L,
        )
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val writer =
          executor.submit {
            start.await()
            repeat(1_000) { index ->
              val value = index + 1
              document.update(
                  version = value,
                  modified = Instant.ofEpochMilli(value.toLong()),
                  content = "content-$value",
                  revision = value.toLong(),
              )
            }
          }
      val reader =
          executor.submit {
            start.await()
            repeat(1_000) {
              val snapshot = document.snapshot()
              assertThat(snapshot.content).isEqualTo("content-${snapshot.version}")
              assertThat(snapshot.revision).isEqualTo(snapshot.version.toLong())
              assertThat(snapshot.modified)
                  .isEqualTo(Instant.ofEpochMilli(snapshot.version.toLong()))
            }
          }
      start.countDown()
      writer.get()
      reader.get()
    } finally {
      executor.shutdownNow()
      Files.deleteIfExists(document.file)
    }
  }

  @Test
  fun revisionsAdvanceAcrossOpenChangeCloseAndReopen() {
    val file = Files.createTempFile("file-manager", ".xml")
    try {
      FileManager.onDocumentOpen(DocumentOpenEvent(file, "open", 1))
      val opened = checkNotNull(FileManager.getActiveDocumentSnapshot(file))

      FileManager.onDocumentContentChange(change(file, "changed", 2))
      val changed = checkNotNull(FileManager.getActiveDocumentSnapshot(file))

      FileManager.onDocumentClose(DocumentCloseEvent(file))
      assertThat(FileManager.getActiveDocumentSnapshot(file)).isNull()

      FileManager.onDocumentOpen(DocumentOpenEvent(file, "reopened", 1))
      val reopened = checkNotNull(FileManager.getActiveDocumentSnapshot(file))

      assertThat(opened.content).isEqualTo("open")
      assertThat(changed.content).isEqualTo("changed")
      assertThat(changed.version).isEqualTo(2)
      assertThat(changed.revision).isGreaterThan(opened.revision)
      assertThat(reopened.version).isEqualTo(1)
      assertThat(reopened.revision).isGreaterThan(changed.revision)
    } finally {
      FileManager.onDocumentClose(DocumentCloseEvent(file))
      Files.deleteIfExists(file)
    }
  }

  @Test
  fun batchSnapshotsUseNormalizedPathsAndOnlyActiveDocuments() {
    val active = Files.createTempFile("file-manager-active", ".xml")
    val inactive = Files.createTempFile("file-manager-inactive", ".xml")
    try {
      FileManager.onDocumentOpen(DocumentOpenEvent(active, "active", 1))

      val snapshots =
          FileManager.getActiveDocumentSnapshots(
              listOf(active.parent.resolve(".").resolve(active.fileName), inactive)
          )

      assertThat(snapshots.keys).containsExactly(active.normalize())
      assertThat(snapshots.getValue(active.normalize()).content).isEqualTo("active")
    } finally {
      FileManager.onDocumentClose(DocumentCloseEvent(active))
      Files.deleteIfExists(active)
      Files.deleteIfExists(inactive)
    }
  }

  private fun change(file: java.nio.file.Path, text: String, version: Int): DocumentChangeEvent {
    return DocumentChangeEvent(
        file,
        text,
        text,
        version,
        ChangeType.NEW_TEXT,
        0,
        Range.NONE,
    )
  }
}
