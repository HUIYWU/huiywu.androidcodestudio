package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseExecutorTest {

  @Test
  fun executePassesExtractedMethodBodyToAttempt() {
    var observedBody: String? = null

    val result =
        PartialReparseExecutor()
            .execute("prefix{body}suffix", 6, 12) { newBody ->
              observedBody = newBody
              true
            }

    assertTrue(result.isSuccess)
    assertEquals(PartialReparseAttemptResult.Status.SUCCESS, result.status)
    assertEquals("{body}", observedBody)
    assertEquals("method body reparsed", result.reason)
  }

  @Test
  fun executeReturnsFailedWhenAttemptReturnsFalse() {
    val result = PartialReparseExecutor().execute("0123456789", 2, 5) { false }

    assertFalse(result.isSuccess)
    assertEquals(PartialReparseAttemptResult.Status.FAILED, result.status)
    assertEquals("PartialReparser.reparseMethod returned false", result.reason)
  }

  @Test
  fun executeReturnsFailedWhenAttemptThrows() {
    val result =
        PartialReparseExecutor().execute("0123456789", 2, 5) {
          throw IllegalStateException("boom")
        }

    assertFalse(result.isSuccess)
    assertEquals(PartialReparseAttemptResult.Status.FAILED, result.status)
    assertEquals("PartialReparser.reparseMethod threw exception", result.reason)
  }

  @Test
  fun executeReturnsFailedWhenBodyStartIsNegative() {
    val result = PartialReparseExecutor().execute("0123456789", -1, 5) { true }

    assertFalse(result.isSuccess)
    assertEquals(PartialReparseAttemptResult.Status.FAILED, result.status)
    assertEquals("method body range is outside document contents", result.reason)
  }

  @Test
  fun executeReturnsFailedWhenBodyEndIsBeforeStart() {
    val result = PartialReparseExecutor().execute("0123456789", 5, 2) { true }

    assertFalse(result.isSuccess)
    assertEquals(PartialReparseAttemptResult.Status.FAILED, result.status)
    assertEquals("method body range is outside document contents", result.reason)
  }

  @Test
  fun executeReturnsFailedWhenBodyEndIsOutsideContents() {
    val result = PartialReparseExecutor().execute("0123456789", 2, 11) { true }

    assertFalse(result.isSuccess)
    assertEquals(PartialReparseAttemptResult.Status.FAILED, result.status)
    assertEquals("method body range is outside document contents", result.reason)
  }
}