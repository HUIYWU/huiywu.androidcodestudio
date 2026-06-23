package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedAttemptExecutorConsumerResultTest {

  @Test
  fun compatibilityShellStillExposesBridgeState() {
    val result = PartialReparseDryRunIsolatedAttemptExecutorConsumerResult.notConsumed("not consumed")

    assertTrue(result.nonExecutingConsumer)
    assertFalse(result.attemptExecutorBridge.bridgeAttempted)
  }

  @Test
  fun consumeFailedCompatibilityShellStillCleansNestedBridge() {
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

    val failed =
        PartialReparseDryRunIsolatedAttemptExecutorConsumerResult.consumeFailed(
            "consume failed",
            bridge,
        )
    val cleaned = failed.cleanupCompleted("cleanup done")

    assertTrue(cleaned.attemptExecutorBridge.isCleanedUp)
    assertTrue(cleaned.isCleanedUp)
  }
}