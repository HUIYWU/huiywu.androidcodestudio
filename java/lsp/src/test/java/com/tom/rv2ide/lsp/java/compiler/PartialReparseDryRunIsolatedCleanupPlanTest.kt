package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCleanupPlanTest {

  @Test
  fun notRequiredPlanIsConservative() {
    val plan = PartialReparseDryRunIsolatedCleanupPlan.notRequired("no cleanup")

    assertEquals(PartialReparseDryRunIsolatedCleanupPlan.State.NOT_REQUIRED, plan.state)
    assertEquals("no cleanup", plan.reason)
    assertFalse(plan.requiresDestroy)
    assertFalse(plan.requiresClose)
    assertFalse(plan.requiresFailureCleanup)
    assertFalse(plan.cleanupOwnedBySession)
    assertFalse(plan.isRequired)
  }

  @Test
  fun requiredPlanCarriesOwnershipFlags() {
    val plan =
        PartialReparseDryRunIsolatedCleanupPlan.required(
            "cleanup required",
            true,
            true,
            true,
            true,
        )

    assertEquals(PartialReparseDryRunIsolatedCleanupPlan.State.REQUIRED, plan.state)
    assertTrue(plan.requiresDestroy)
    assertTrue(plan.requiresClose)
    assertTrue(plan.requiresFailureCleanup)
    assertTrue(plan.cleanupOwnedBySession)
    assertTrue(plan.isRequired)
  }

  @Test
  fun completeIsIdempotent() {
    val required =
        PartialReparseDryRunIsolatedCleanupPlan.required(
            "cleanup required",
            true,
            false,
            true,
            false,
        )

    val completed = required.complete("cleanup done")
    val completedAgain = completed.complete("cleanup done again")

    assertEquals(PartialReparseDryRunIsolatedCleanupPlan.State.COMPLETED, completed.state)
    assertEquals("cleanup done", completed.reason)
    assertFalse(completed.requiresDestroy)
    assertFalse(completed.requiresClose)
    assertFalse(completed.requiresFailureCleanup)
    assertFalse(completed.cleanupOwnedBySession)
    assertTrue(completed.isCompleted)
    assertSame(completed, completedAgain)
  }
}