package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedAttemptExecutorConsumerResultTest {

  @Test
  fun notConsumedIsConservativeAndStubbed() {
    val result = PartialReparseDryRunIsolatedAttemptExecutorConsumerResult.notConsumed("not consumed")

    assertEquals(
        PartialReparseDryRunIsolatedAttemptExecutorConsumerResult.State.NOT_CONSUMED,
        result.state,
    )
    assertFalse(result.consumeAttempted)
    assertFalse(result.consumeFailed)
    assertTrue(result.nonExecutingConsumer)
    assertFalse(result.attemptExecutorBridge.bridgeAttempted)
  }

  @Test
  fun deferredConsumerPreservesDeferredBridge() {
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

    val result =
        PartialReparseDryRunIsolatedAttemptExecutorConsumerResult.deferred(
            "consumer deferred",
            bridge,
        )

    assertEquals(
        PartialReparseDryRunIsolatedAttemptExecutorConsumerResult.State.DEFERRED,
        result.state,
    )
    assertFalse(result.consumeAttempted)
    assertFalse(result.consumeFailed)
    assertTrue(result.nonExecutingConsumer)
    assertTrue(result.attemptExecutorBridge.isDeferred)
    assertTrue(result.isDeferred)
  }

  @Test
  fun consumeFailedCanBeCleanedUp() {
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

    val failed =
        PartialReparseDryRunIsolatedAttemptExecutorConsumerResult.consumeFailed(
            "consume failed",
            bridge,
        )
    val cleaned = failed.cleanupCompleted("cleanup done")

    assertTrue(failed.consumeAttempted)
    assertTrue(failed.consumeFailed)
    assertTrue(failed.isConsumeFailed)
    assertEquals(
        PartialReparseDryRunIsolatedAttemptExecutorConsumerResult.State.CLEANED_UP,
        cleaned.state,
    )
    assertTrue(cleaned.attemptExecutorBridge.isCleanedUp)
    assertTrue(cleaned.isCleanedUp)
  }
}