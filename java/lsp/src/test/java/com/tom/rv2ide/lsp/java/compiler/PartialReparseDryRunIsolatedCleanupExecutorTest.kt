package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCleanupExecutorTest {

  @Test
  fun notNeededExecutorIsConservative() {
    val executor = PartialReparseDryRunIsolatedCleanupExecutor.notNeeded("none")

    assertEquals(PartialReparseDryRunIsolatedCleanupExecutor.State.NOT_NEEDED, executor.state)
    assertFalse(executor.cleanupPlan.isRequired)
    assertFalse(executor.compilerSlot.hasReservedSlot)
    assertFalse(executor.shouldRunOnFailure)
    assertFalse(executor.shouldRunOnSuccess)
    assertFalse(executor.isPending)
  }

  @Test
  fun pendingExecutorCarriesCleanupAndSlot() {
    val cleanupPlan =
        PartialReparseDryRunIsolatedCleanupPlan.required("cleanup", true, true, true, true)
    val compilerSlot = PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true)

    val executor =
        PartialReparseDryRunIsolatedCleanupExecutor.pending(
            "pending cleanup",
            cleanupPlan,
            compilerSlot,
            true,
            false,
        )

    assertEquals(PartialReparseDryRunIsolatedCleanupExecutor.State.PENDING, executor.state)
    assertTrue(executor.cleanupPlan.isRequired)
    assertTrue(executor.compilerSlot.hasReservedSlot)
    assertTrue(executor.shouldRunOnFailure)
    assertFalse(executor.shouldRunOnSuccess)
    assertTrue(executor.isPending)
  }

  @Test
  fun executeIsIdempotent() {
    val executor =
        PartialReparseDryRunIsolatedCleanupExecutor.pending(
            "pending cleanup",
            PartialReparseDryRunIsolatedCleanupPlan.required("cleanup", true, false, true, true),
            PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true),
            true,
            false,
        )

    val executed = executor.execute("executed cleanup")
    val executedAgain = executed.execute("executed cleanup again")

    assertEquals(PartialReparseDryRunIsolatedCleanupExecutor.State.EXECUTED, executed.state)
    assertTrue(executed.cleanupPlan.isCompleted)
    assertTrue(executed.compilerSlot.isReleased)
    assertFalse(executed.shouldRunOnFailure)
    assertFalse(executed.shouldRunOnSuccess)
    assertTrue(executed.isExecuted)
    assertSame(executed, executedAgain)
  }
}