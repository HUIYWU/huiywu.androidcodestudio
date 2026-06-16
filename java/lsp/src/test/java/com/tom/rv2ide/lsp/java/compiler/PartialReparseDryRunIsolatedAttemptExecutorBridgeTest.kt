package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedAttemptExecutorBridgeTest {

  @Test
  fun notBridgedIsConservativeAndStubbed() {
    val bridge = PartialReparseDryRunIsolatedAttemptExecutorBridge.notBridged("not bridged")

    assertEquals(
        PartialReparseDryRunIsolatedAttemptExecutorBridge.State.NOT_BRIDGED,
        bridge.state,
    )
    assertFalse(bridge.bridgeAttempted)
    assertFalse(bridge.bridgeFailed)
    assertTrue(bridge.nonExecutingBridge)
    assertFalse(bridge.executionAttemptResult.attemptStarted)
  }

  @Test
  fun deferredBridgePreservesDeferredExecutionAttempt() {
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

    assertEquals(
        PartialReparseDryRunIsolatedAttemptExecutorBridge.State.DEFERRED,
        bridge.state,
    )
    assertFalse(bridge.bridgeAttempted)
    assertFalse(bridge.bridgeFailed)
    assertTrue(bridge.nonExecutingBridge)
    assertTrue(bridge.executionAttemptResult.isDeferred)
    assertTrue(bridge.isDeferred)
  }

  @Test
  fun bridgeFailedCanBeCleanedUp() {
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

    val failed =
        PartialReparseDryRunIsolatedAttemptExecutorBridge.bridgeFailed(
            "bridge failed",
            attempt,
        )
    val cleaned = failed.cleanupCompleted("cleanup done")

    assertTrue(failed.bridgeAttempted)
    assertTrue(failed.bridgeFailed)
    assertTrue(failed.isBridgeFailed)
    assertEquals(
        PartialReparseDryRunIsolatedAttemptExecutorBridge.State.CLEANED_UP,
        cleaned.state,
    )
    assertTrue(cleaned.executionAttemptResult.isCleanedUp)
    assertTrue(cleaned.isCleanedUp)
  }
}