package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedSessionTest {

  @Test
  fun notAvailableRequiresCompilerCopyButNotCloseAndNoLiveMutation() {
    val session = PartialReparseDryRunIsolatedSession.notAvailable("not ready")

    assertEquals(PartialReparseDryRunIsolatedSession.State.NOT_AVAILABLE, session.state)
    assertEquals("not ready", session.reason)
    assertTrue(session.requiresCompilerCopy)
    assertFalse(session.requiresClose)
    assertFalse(session.cleanupPlan.isRequired)
    assertFalse(session.mayMutateLiveCompilerState)
    assertFalse(session.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(session.requiresFreshReusableCompiler)
    assertTrue(session.cachedCompileMustStartEmpty)
    assertFalse(session.isReady)
    assertFalse(session.isClosed)
  }

  @Test
  fun readyMayRequireCloseButStillMustNotAllowLiveMutation() {
    val session =
        PartialReparseDryRunIsolatedSession.ready(
            "ready",
            true,
            true,
            true,
            true,
        )

    assertEquals(PartialReparseDryRunIsolatedSession.State.READY, session.state)
    assertEquals("ready", session.reason)
    assertTrue(session.requiresCompilerCopy)
    assertTrue(session.requiresClose)
    assertTrue(session.cleanupPlan.isRequired)
    assertTrue(session.cleanupPlan.cleanupOwnedBySession)
    assertFalse(session.mayMutateLiveCompilerState)
    assertTrue(session.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(session.requiresFreshReusableCompiler)
    assertTrue(session.cachedCompileMustStartEmpty)
    assertTrue(session.isReady)
    assertFalse(session.isClosed)
  }

  @Test
  fun closedSessionIsMarkedClosedAndNotReady() {
    val session = PartialReparseDryRunIsolatedSession.closed("closed")

    assertEquals(PartialReparseDryRunIsolatedSession.State.CLOSED, session.state)
    assertEquals("closed", session.reason)
    assertTrue(session.requiresCompilerCopy)
    assertFalse(session.requiresClose)
    assertTrue(session.cleanupPlan.isCompleted)
    assertFalse(session.mayMutateLiveCompilerState)
    assertFalse(session.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(session.requiresFreshReusableCompiler)
    assertTrue(session.cachedCompileMustStartEmpty)
    assertFalse(session.isReady)
    assertTrue(session.isClosed)
  }
}