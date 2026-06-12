package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedSessionCandidateTest {

  @Test
  fun notAvailableCandidateIsConservative() {
    val candidate = PartialReparseDryRunIsolatedSessionCandidate.notAvailable("missing")

    assertEquals(PartialReparseDryRunIsolatedSessionCandidate.State.NOT_AVAILABLE, candidate.state)
    assertEquals("missing", candidate.reason)
    assertFalse(candidate.hasCompilerCopyCandidate)
    assertFalse(candidate.requiresDestroy)
    assertFalse(candidate.requiresClose)
    assertFalse(candidate.canExecuteDryRun)
    assertFalse(candidate.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(candidate.requiresFreshReusableCompiler)
    assertTrue(candidate.cachedCompileMustStartEmpty)
    assertFalse(candidate.isCreated)
    assertFalse(candidate.isClosed)
  }

  @Test
  fun createdCandidateCarriesLifecycleContractButCannotExecuteYet() {
    val candidate =
        PartialReparseDryRunIsolatedSessionCandidate.created(
            "candidate created",
            true,
            true,
            true,
            true,
            true,
        )

    assertEquals(PartialReparseDryRunIsolatedSessionCandidate.State.CREATED, candidate.state)
    assertEquals("candidate created", candidate.reason)
    assertTrue(candidate.hasCompilerCopyCandidate)
    assertTrue(candidate.requiresDestroy)
    assertTrue(candidate.requiresClose)
    assertFalse(candidate.canExecuteDryRun)
    assertTrue(candidate.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(candidate.requiresFreshReusableCompiler)
    assertTrue(candidate.cachedCompileMustStartEmpty)
    assertTrue(candidate.isCreated)
    assertFalse(candidate.isClosed)
  }

  @Test
  fun closeReturnsClosedCandidateAndIsIdempotent() {
    val created =
        PartialReparseDryRunIsolatedSessionCandidate.created(
            "candidate created",
            true,
            true,
            true,
            true,
            true,
        )

    val closed = created.close("candidate closed")
    val closedAgain = closed.close("ignored")

    assertEquals(PartialReparseDryRunIsolatedSessionCandidate.State.CLOSED, closed.state)
    assertEquals("candidate closed", closed.reason)
    assertFalse(closed.hasCompilerCopyCandidate)
    assertFalse(closed.requiresDestroy)
    assertFalse(closed.requiresClose)
    assertFalse(closed.canExecuteDryRun)
    assertTrue(closed.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(closed.requiresFreshReusableCompiler)
    assertTrue(closed.cachedCompileMustStartEmpty)
    assertFalse(closed.isCreated)
    assertTrue(closed.isClosed)
    assertSame(closed, closedAgain)
  }
}
