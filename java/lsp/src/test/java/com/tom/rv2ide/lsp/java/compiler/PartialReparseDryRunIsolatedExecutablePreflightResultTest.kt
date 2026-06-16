package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedExecutablePreflightResultTest {

  @Test
  fun notReadyPreflightIsConservative() {
    val result = PartialReparseDryRunIsolatedExecutablePreflightResult.notReady("not ready")

    assertEquals(
        PartialReparseDryRunIsolatedExecutablePreflightResult.State.NOT_READY,
        result.state,
    )
    assertFalse(result.preflightAttempted)
    assertFalse(result.preflightFailed)
    assertFalse(result.sessionReadinessResult.session.isReady)
  }

  @Test
  fun deferredPreflightPreservesDeferredSessionReadiness() {
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

    val result =
        PartialReparseDryRunIsolatedExecutablePreflightResult.deferred(
            "preflight deferred",
            readiness,
        )

    assertEquals(
        PartialReparseDryRunIsolatedExecutablePreflightResult.State.DEFERRED,
        result.state,
    )
    assertFalse(result.preflightAttempted)
    assertFalse(result.preflightFailed)
    assertTrue(result.sessionReadinessResult.isDeferred)
    assertTrue(result.isDeferred)
  }

  @Test
  fun precheckFailedCanBeCleanedUp() {
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

    val failed =
        PartialReparseDryRunIsolatedExecutablePreflightResult.precheckFailed(
            "precheck failed",
            readiness,
        )
    val cleaned = failed.cleanupCompleted("cleanup done")

    assertTrue(failed.preflightAttempted)
    assertTrue(failed.preflightFailed)
    assertTrue(failed.isPrecheckFailed)
    assertEquals(
        PartialReparseDryRunIsolatedExecutablePreflightResult.State.CLEANED_UP,
        cleaned.state,
    )
    assertTrue(cleaned.sessionReadinessResult.isCleanedUp)
    assertTrue(cleaned.isCleanedUp)
  }
}