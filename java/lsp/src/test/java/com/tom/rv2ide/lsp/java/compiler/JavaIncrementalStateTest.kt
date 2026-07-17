package com.tom.rv2ide.lsp.java.compiler

import com.tom.rv2ide.eventbus.events.editor.ChangeType
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaIncrementalStateTest {

  @Test
  fun onDocumentChange_accumulatesDeltaAndTracksLatestRangeAndCursor() {
    val state = JavaIncrementalState()
    val firstRange = Range(Position(0, 1, 1), Position(0, 2, 2))
    val secondRange = Range(Position(0, 5, 5), Position(0, 7, 7))

    state.onDocumentChange(changeEvent(delta = 2, range = firstRange))
    state.onDocumentChange(changeEvent(delta = -1, range = secondRange))

    assertEquals(1, state.changeDelta)
    assertSame(secondRange, state.latestChangeRange)
    assertEquals(Position(0, 7, 7), state.newCursorPosition)
  }

  @Test
  fun isChangeValidForReparse_isTrueBeforeAnySuccessfulReparse() {
    val state = JavaIncrementalState()

    state.onDocumentChange(changeEvent(delta = 1, range = Range(Position(3, 0, 10), Position(3, 1, 11))))

    assertTrue(state.isChangeValidForReparse)
  }

  @Test
  fun isChangeValidForReparse_isTrueWhenNextChangeStaysOnLastReparseLine() {
    val state = JavaIncrementalState()

    state.onDocumentChange(changeEvent(delta = 1, range = Range(Position(2, 0, 10), Position(2, 1, 11))))
    state.markReparseSucceeded()
    state.onDocumentChange(changeEvent(delta = 1, range = Range(Position(2, 2, 12), Position(2, 3, 13))))

    assertTrue(state.isChangeValidForReparse)
  }

  @Test
  fun isChangeValidForReparse_isFalseWhenNextChangeMovesToDifferentLine() {
    val state = JavaIncrementalState()

    state.onDocumentChange(changeEvent(delta = 1, range = Range(Position(2, 0, 10), Position(2, 1, 11))))
    state.markReparseSucceeded()
    state.onDocumentChange(changeEvent(delta = 1, range = Range(Position(3, 0, 20), Position(3, 1, 21))))

    assertFalse(state.isChangeValidForReparse)
  }

  @Test
  fun markReparseSucceeded_resetsDeltaButKeepsLatestChangeRange() {
    val state = JavaIncrementalState()
    val range = Range(Position(2, 0, 10), Position(2, 1, 11))

    state.onDocumentChange(changeEvent(delta = 5, range = range))
    state.markReparseSucceeded()

    assertEquals(0, state.changeDelta)
    assertSame(range, state.latestChangeRange)
    assertEquals(Position(2, 1, 11), state.newCursorPosition)
  }

  @Test
  fun resetAfterFullRecompile_clearsAllIncrementalState() {
    val state = JavaIncrementalState()
    val range = Range(Position(2, 0, 10), Position(2, 1, 11))

    state.onDocumentChange(changeEvent(delta = 5, range = range))
    state.resetAfterFullRecompile()

    assertEquals(0, state.changeDelta)
    assertNull(state.latestChangeRange)
    assertEquals(Position.NONE, state.newCursorPosition)
    assertTrue(state.isChangeValidForReparse)
  }

  @Test
  fun resetForCopy_clearsAllIncrementalState() {
    val state = JavaIncrementalState()

    state.onDocumentChange(changeEvent(delta = 5, range = Range(Position(2, 0, 10), Position(2, 1, 11))))
    state.markReparseSucceeded()
    state.resetForCopy()

    assertEquals(0, state.changeDelta)
    assertNull(state.latestChangeRange)
    assertEquals(Position.NONE, state.newCursorPosition)
    assertTrue(state.isChangeValidForReparse)
  }

  private fun changeEvent(delta: Int, range: Range): DocumentChangeEvent {
    return DocumentChangeEvent(
        Paths.get("/tmp/A.java"),
        "x",
        null,
        1,
        ChangeType.INSERT,
        delta,
        range,
    )
  }
}