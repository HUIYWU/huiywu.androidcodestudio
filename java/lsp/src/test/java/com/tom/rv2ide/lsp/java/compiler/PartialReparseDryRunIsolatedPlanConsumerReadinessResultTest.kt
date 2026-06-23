package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedPlanConsumerReadinessResultTest {

  @Test
  fun notReadyIsConservativeAndStubbed() {
    val result = PartialReparseDryRunIsolatedPlanConsumerReadinessResult.notReady("not ready")

    assertEquals(
        PartialReparseDryRunIsolatedPlanConsumerReadinessResult.State.NOT_READY,
        result.state,
    )
    assertFalse(result.readinessCheckAttempted)
    assertFalse(result.readinessFailed)
    assertTrue(result.nonExecutingPlanConsumer)
    assertFalse(result.attemptExecutorConsumerResult.consumeAttempted)
  }

  @Test
  fun deferredPreservesDeferredAttemptExecutorConsumer() {
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
    val bridge =
        PartialReparseDryRunIsolatedAttemptExecutorBridge.deferred(
            "bridge deferred",
            attempt,
        )
    val consumer =
        PartialReparseDryRunIsolatedAttemptExecutorConsumerResult.deferred(
            "consumer deferred",
            bridge,
        )

    val result =
        PartialReparseDryRunIsolatedPlanConsumerReadinessResult.deferred(
            "plan consumer deferred",
            consumer,
        )

    assertEquals(
        PartialReparseDryRunIsolatedPlanConsumerReadinessResult.State.DEFERRED,
        result.state,
    )
    assertFalse(result.readinessCheckAttempted)
    assertFalse(result.readinessFailed)
    assertTrue(result.nonExecutingPlanConsumer)
    assertTrue(result.attemptExecutorConsumerResult.isDeferred)
    assertTrue(result.isDeferred)
  }

  @Test
  fun readyFailedCanBeCleanedUp() {
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
    val bridge =
        PartialReparseDryRunIsolatedAttemptExecutorBridge.bridged(
            "bridge ready",
            attempt,
            true,
        )
    val consumer =
        PartialReparseDryRunIsolatedAttemptExecutorConsumerResult.consumed(
            "consumer ready",
            bridge,
            true,
        )

    val failed =
        PartialReparseDryRunIsolatedPlanConsumerReadinessResult.readyFailed(
            "plan consumer failed",
            consumer,
        )
    val cleaned = failed.cleanupCompleted("cleanup done")

    assertTrue(failed.readinessCheckAttempted)
    assertTrue(failed.readinessFailed)
    assertTrue(failed.isReadyFailed)
    assertEquals(
        PartialReparseDryRunIsolatedPlanConsumerReadinessResult.State.CLEANED_UP,
        cleaned.state,
    )
    assertTrue(cleaned.attemptExecutorConsumerResult.isCleanedUp)
    assertTrue(cleaned.isCleanedUp)
  }
}