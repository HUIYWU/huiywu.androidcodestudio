package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCompilerCreationGuardTest {

  @Test
  fun notAllowedGuardIsConservative() {
    val guard = PartialReparseDryRunIsolatedCompilerCreationGuard.notAllowed("blocked")

    assertEquals(PartialReparseDryRunIsolatedCompilerCreationGuard.State.NOT_ALLOWED, guard.state)
    assertEquals("blocked", guard.reason)
    assertFalse(guard.mayCreateCompilerCopy)
    assertFalse(guard.mayMutateLiveCompilerState)
    assertFalse(guard.isAllowed)
  }

  @Test
  fun allowedGuardStillForbidsLiveStateMutation() {
    val guard = PartialReparseDryRunIsolatedCompilerCreationGuard.allowed("allowed")

    assertEquals(PartialReparseDryRunIsolatedCompilerCreationGuard.State.ALLOWED, guard.state)
    assertEquals("allowed", guard.reason)
    assertTrue(guard.mayCreateCompilerCopy)
    assertFalse(guard.mayMutateLiveCompilerState)
    assertTrue(guard.isAllowed)
  }
}