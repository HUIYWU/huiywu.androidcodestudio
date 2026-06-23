package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedExecutionConsumerObservationTest {

  @Test
  fun notReadyIsConservativeAndStubbed() {
    val observation =
        PartialReparseDryRunIsolatedExecutionConsumerObservation.notReady("not ready")

    assertEquals(
        PartialReparseDryRunIsolatedExecutionConsumerObservation.State.NOT_READY,
        observation.state,
    )
    assertFalse(observation.bridgeAttempted)
    assertFalse(observation.bridgeFailed)
    assertFalse(observation.consumeAttempted)
    assertFalse(observation.consumeFailed)
    assertFalse(observation.readinessCheckAttempted)
    assertFalse(observation.readinessFailed)
    assertTrue(observation.nonExecuting)
    assertFalse(observation.executionAttemptResult.preflightResult.session.isReady)
  }

  @Test
  fun deferredPreservesDeferredExecutionAttempt() {
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
    val attempt =
        PartialReparseDryRunIsolatedExecutionAttemptResult.deferred(
            "attempt deferred",
            preflight,
        )

    val observation =
        PartialReparseDryRunIsolatedExecutionConsumerObservation.deferred(
            "observation deferred",
            attempt,
            false,
            false,
            false,
            false,
            true,
        )

    assertEquals(
        PartialReparseDryRunIsolatedExecutionConsumerObservation.State.DEFERRED,
        observation.state,
    )
    assertFalse(observation.bridgeAttempted)
    assertFalse(observation.bridgeFailed)
    assertFalse(observation.consumeAttempted)
    assertFalse(observation.consumeFailed)
    assertFalse(observation.readinessCheckAttempted)
    assertFalse(observation.readinessFailed)
    assertTrue(observation.nonExecuting)
    assertTrue(observation.executionAttemptResult.isDeferred)
    assertTrue(observation.isDeferred)
  }

  @Test
  fun readyStateMarksObservationReady() {
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
    val attempt =
        PartialReparseDryRunIsolatedExecutionAttemptResult.started(
            "attempt started",
            preflight,
            true,
        )

    val observation =
        PartialReparseDryRunIsolatedExecutionConsumerObservation.ready(
            "observation ready",
            attempt,
            true,
            true,
            true,
        )

    assertEquals(
        PartialReparseDryRunIsolatedExecutionConsumerObservation.State.READY,
        observation.state,
    )
    assertTrue(observation.bridgeAttempted)
    assertFalse(observation.bridgeFailed)
    assertTrue(observation.consumeAttempted)
    assertFalse(observation.consumeFailed)
    assertTrue(observation.readinessCheckAttempted)
    assertFalse(observation.readinessFailed)
    assertTrue(observation.nonExecuting)
    assertTrue(observation.executionAttemptResult.isStarted)
    assertTrue(observation.isReady)
  }

  @Test
  fun failedObservationCanBeCleanedUp() {
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
    val attempt =
        PartialReparseDryRunIsolatedExecutionAttemptResult.attemptFailed(
            "attempt failed",
            preflight,
        )

    val failed =
        PartialReparseDryRunIsolatedExecutionConsumerObservation.failed(
            "observation failed",
            attempt,
            true,
            true,
        )
    val cleaned = failed.cleanupCompleted("cleanup done")

    assertTrue(failed.bridgeAttempted)
    assertTrue(failed.bridgeFailed)
    assertTrue(failed.consumeAttempted)
    assertTrue(failed.consumeFailed)
    assertTrue(failed.readinessCheckAttempted)
    assertTrue(failed.readinessFailed)
    assertTrue(failed.isFailed)
    assertEquals(
        PartialReparseDryRunIsolatedExecutionConsumerObservation.State.CLEANED_UP,
        cleaned.state,
    )
    assertTrue(cleaned.executionAttemptResult.isCleanedUp)
    assertTrue(cleaned.isCleanedUp)
  }
}
