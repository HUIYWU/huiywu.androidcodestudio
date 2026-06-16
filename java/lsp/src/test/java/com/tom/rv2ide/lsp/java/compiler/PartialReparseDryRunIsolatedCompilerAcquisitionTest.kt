package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCompilerAcquisitionTest {

  @Test
  fun notAcquiredStateIsConservative() {
    val acquisition = PartialReparseDryRunIsolatedCompilerAcquisition.notAcquired("not acquired")

    assertEquals(PartialReparseDryRunIsolatedCompilerAcquisition.State.NOT_ACQUIRED, acquisition.state)
    assertFalse(acquisition.acquisitionAttempted)
    assertFalse(acquisition.acquisitionFailed)
    assertFalse(acquisition.compilerSlot.hasReservedSlot)
    assertFalse(acquisition.cleanupExecutor.isPending)
  }

  @Test
  fun reservedStateKeepsSlotWithoutAcquiringObject() {
    val slot = PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true)
    val cleanupExecutor =
        PartialReparseDryRunIsolatedCleanupExecutor.pending(
            "cleanup pending",
            PartialReparseDryRunIsolatedCleanupPlan.required("cleanup", true, true, true, true),
            slot,
            true,
            false,
        )

    val acquisition =
        PartialReparseDryRunIsolatedCompilerAcquisition.reserved(
            "reserved acquisition",
            slot,
            cleanupExecutor,
        )

    assertEquals(PartialReparseDryRunIsolatedCompilerAcquisition.State.RESERVED, acquisition.state)
    assertTrue(acquisition.compilerSlot.hasReservedSlot)
    assertFalse(acquisition.compilerSlot.hasCompilerObject)
    assertFalse(acquisition.acquisitionAttempted)
    assertFalse(acquisition.acquisitionFailed)
    assertTrue(acquisition.isReserved)
  }

  @Test
  fun failedAcquisitionCanBeCleanedUp() {
    val slot = PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true)
    val cleanupExecutor =
        PartialReparseDryRunIsolatedCleanupExecutor.pending(
            "cleanup pending",
            PartialReparseDryRunIsolatedCleanupPlan.required("cleanup", true, true, true, true),
            slot,
            true,
            false,
        )
    val failed =
        PartialReparseDryRunIsolatedCompilerAcquisition.failed(
            "acquisition failed",
            slot,
            cleanupExecutor,
        )

    val cleanedUp = failed.cleanupCompleted("cleanup done")

    assertEquals(PartialReparseDryRunIsolatedCompilerAcquisition.State.FAILED, failed.state)
    assertTrue(failed.acquisitionAttempted)
    assertTrue(failed.acquisitionFailed)
    assertTrue(failed.isFailed)
    assertEquals(PartialReparseDryRunIsolatedCompilerAcquisition.State.CLEANED_UP, cleanedUp.state)
    assertTrue(cleanedUp.cleanupExecutor.isExecuted)
    assertTrue(cleanedUp.compilerSlot.isReleased)
    assertTrue(cleanedUp.isCleanedUp)
  }
}