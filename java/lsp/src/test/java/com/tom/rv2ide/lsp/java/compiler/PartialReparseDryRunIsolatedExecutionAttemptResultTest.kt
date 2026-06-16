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
    assertFalse(result.preflightResult.sessionReadinessResult.session.isReady)
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
    val readiness =
        PartialReparseDryRunIsolatedSessionReadinessResult.deferred(
            "session deferred",
            session,
        )
    val preflight =
        PartialReparseDryRunIsolatedExecutablePreflightResult.deferred(
            "preflight deferred",
            readiness,
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
    val readiness =
        PartialReparseDryRunIsolatedSessionReadinessResult.ready(
            "session readiness ready",
            session,
        )
    val preflight =
        PartialReparseDryRunIsolatedExecutablePreflightResult.ready(
            "preflight ready",
            readiness,
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