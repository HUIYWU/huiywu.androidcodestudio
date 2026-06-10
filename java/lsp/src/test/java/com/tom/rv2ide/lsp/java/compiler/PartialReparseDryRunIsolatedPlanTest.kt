package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedPlanTest {

  @Test
  fun notAvailableRequiresCompilerCopyButDoesNotAllowLiveMutation() {
    val plan = PartialReparseDryRunIsolatedPlan.notAvailable("not implemented")

    assertEquals(PartialReparseDryRunIsolatedPlan.State.NOT_AVAILABLE, plan.state)
    assertEquals("not implemented", plan.reason)
    assertTrue(plan.requiresCompilerCopy)
    assertFalse(plan.mayMutateLiveCompilerState)
    assertFalse(plan.isReady)
  }

  @Test
  fun readyRequiresCompilerCopyButDoesNotAllowLiveMutation() {
    val plan = PartialReparseDryRunIsolatedPlan.ready("ready")

    assertEquals(PartialReparseDryRunIsolatedPlan.State.READY, plan.state)
    assertEquals("ready", plan.reason)
    assertTrue(plan.requiresCompilerCopy)
    assertFalse(plan.mayMutateLiveCompilerState)
    assertTrue(plan.isReady)
  }
}