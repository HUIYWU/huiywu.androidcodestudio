package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedExecutionAttemptResultTest {

  @Test
  fun notStartedIsConservativeAndStubbed() {
    val result = PartialReparseDryRunIsolatedExecutionAttemptResult.notStarted("not started")

    assertEquals(
        PartialReparseDryRunIsolatedExecutionAttemptResult.State.NOT_STARTED,
        result.state,
    )
    assertFalse(result.attemptStarted)
    assertFalse(result.attemptFailed)
    assertTrue(result.nonExecutingStub)
    assertFalse(result.preflightResult.session.isReady)

  }

  @Test
  fun deferredAttemptPreservesDeferredPreflight() {
    val session =
        PartialReparseDryRunIsolatedSession.ready(
            "session ready",
            true,
            true,
            true,
            true,
        )
    val preflight =
        PartialReparseDryRunIsolatedSessionExecutionPreflight.deferred(
            "preflight deferred",
            session,
        )

    val result =
        PartialReparseDryRunIsolatedExecutionAttemptResult.deferred(
            "attempt deferred",
            preflight,
        )

    assertEquals(
        PartialReparseDryRunIsolatedExecutionAttemptResult.State.DEFERRED,
        result.state,
    )
    assertFalse(result.attemptStarted)
    assertFalse(result.attemptFailed)
    assertTrue(result.nonExecutingStub)
    assertTrue(result.preflightResult.isDeferred)
    assertTrue(result.isDeferred)
  }

  @Test
  fun attemptFailedCanBeCleanedUp() {
    val session =
        PartialReparseDryRunIsolatedSession.ready(
            "session ready",
            true,
            true,
            true,
            true,
        )
    val preflight =
        PartialReparseDryRunIsolatedSessionExecutionPreflight.ready(
            "preflight ready",
            session,
        )

    val failed =
        PartialReparseDryRunIsolatedExecutionAttemptResult.attemptFailed(
            "attempt failed",
            preflight,
        )
    val cleaned = failed.cleanupCompleted("cleanup done")

    assertTrue(failed.attemptStarted)
    assertTrue(failed.attemptFailed)
    assertTrue(failed.isAttemptFailed)
    assertEquals(
        PartialReparseDryRunIsolatedExecutionAttemptResult.State.CLEANED_UP,
        cleaned.state,
    )
    assertTrue(cleaned.preflightResult.isCleanedUp)
    assertTrue(cleaned.isCleanedUp)
  }
}