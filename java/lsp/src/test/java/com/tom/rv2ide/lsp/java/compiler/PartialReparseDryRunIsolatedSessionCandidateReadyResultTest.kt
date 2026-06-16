package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedSessionCandidateReadyResultTest {

  @Test
  fun notReadyStateIsConservative() {
    val result = PartialReparseDryRunIsolatedSessionCandidateReadyResult.notReady("not ready")

    assertEquals(
        PartialReparseDryRunIsolatedSessionCandidateReadyResult.State.NOT_READY,
        result.state,
    )
    assertFalse(result.readyCheckAttempted)
    assertFalse(result.readyFailed)
    assertFalse(result.candidate.isCreated)
  }

  @Test
  fun deferredStateKeepsCandidateCreatedButNotReady() {
    val candidate =
        PartialReparseDryRunIsolatedSessionCandidate.created(
            "candidate created",
            true,
            true,
            true,
            true,
            true,
        )

    val result =
        PartialReparseDryRunIsolatedSessionCandidateReadyResult.deferred(
            "candidate deferred",
            candidate,
        )

    assertEquals(
        PartialReparseDryRunIsolatedSessionCandidateReadyResult.State.DEFERRED,
        result.state,
    )
    assertFalse(result.readyCheckAttempted)
    assertFalse(result.readyFailed)
    assertTrue(result.candidate.isCreated)
    assertTrue(result.isDeferred)
  }

  @Test
  fun readyFailedCanBeCleanedUp() {
    val candidate =
        PartialReparseDryRunIsolatedSessionCandidate.created(
            "candidate created",
            true,
            true,
            true,
            true,
            true,
        )

    val failed =
        PartialReparseDryRunIsolatedSessionCandidateReadyResult.readyFailed(
            "ready failed",
            candidate,
        )

    val cleaned = failed.cleanupCompleted("cleanup done")

    assertTrue(failed.readyCheckAttempted)
    assertTrue(failed.readyFailed)
    assertTrue(failed.isReadyFailed)
    assertEquals(
        PartialReparseDryRunIsolatedSessionCandidateReadyResult.State.CLEANED_UP,
        cleaned.state,
    )
    assertTrue(cleaned.candidate.isClosed)
    assertTrue(cleaned.isCleanedUp)
  }
}