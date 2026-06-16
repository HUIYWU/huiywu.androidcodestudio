package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedSessionReadinessResultTest {

  @Test
  fun notReadyStateIsConservative() {
    val result = PartialReparseDryRunIsolatedSessionReadinessResult.notReady("not ready")

    assertEquals(
        PartialReparseDryRunIsolatedSessionReadinessResult.State.NOT_READY,
        result.state,
    )
    assertFalse(result.readinessCheckAttempted)
    assertFalse(result.readinessFailed)
    assertFalse(result.session.isReady)
  }

  @Test
  fun deferredStateKeepsSessionReadyButNotExecutableReady() {
    val session =
        PartialReparseDryRunIsolatedSession.ready(
            "session ready",
            true,
            true,
            true,
            true,
        )

    val result =
        PartialReparseDryRunIsolatedSessionReadinessResult.deferred(
            "session deferred",
            session,
        )

    assertEquals(
        PartialReparseDryRunIsolatedSessionReadinessResult.State.DEFERRED,
        result.state,
    )
    assertFalse(result.readinessCheckAttempted)
    assertFalse(result.readinessFailed)
    assertTrue(result.session.isReady)
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

    val failed =
        PartialReparseDryRunIsolatedSessionReadinessResult.readyFailed(
            "ready failed",
            session,
        )

    val cleaned = failed.cleanupCompleted("cleanup done")

    assertTrue(failed.readinessCheckAttempted)
    assertTrue(failed.readinessFailed)
    assertTrue(failed.isReadyFailed)
    assertEquals(
        PartialReparseDryRunIsolatedSessionReadinessResult.State.CLEANED_UP,
        cleaned.state,
    )
    assertTrue(cleaned.session.isClosed)
    assertTrue(cleaned.isCleanedUp)
  }
}