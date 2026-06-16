package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCompilerObjectAcquisitionResultTest {

  @Test
  fun notRequestedStateIsConservative() {
    val result =
        PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult.notRequested("not requested")

    assertEquals(
        PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult.State.NOT_REQUESTED,
        result.state,
    )
    assertFalse(result.acquisitionAttempted)
    assertFalse(result.acquisitionFailed)
    assertFalse(result.acquisition.compilerSlot.hasReservedSlot)
  }

  @Test
  fun reservedStateKeepsLifecycleOpenWithoutRealObject() {
    val result =
        PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult.reserved(
            "reserved",
            PartialReparseDryRunIsolatedCompilerAcquisition.reserved(
                "acquisition reserved",
                PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true),
                PartialReparseDryRunIsolatedCleanupExecutor.pending(
                    "cleanup pending",
                    PartialReparseDryRunIsolatedCleanupPlan.required("cleanup", true, true, true, true),
                    PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true),
                    true,
                    false,
                ),
            ),
        )

    assertEquals(
        PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult.State.RESERVED,
        result.state,
    )
    assertFalse(result.acquisitionAttempted)
    assertFalse(result.acquisitionFailed)
    assertTrue(result.acquisition.isReserved)
    assertTrue(result.isReserved)
  }

  @Test
  fun acquisitionFailedCanBeCleanedUp() {
    val failed =
        PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult.acquisitionFailed(
            "acquisition failed",
            PartialReparseDryRunIsolatedCompilerAcquisition.failed(
                "failed",
                PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true),
                PartialReparseDryRunIsolatedCleanupExecutor.pending(
                    "cleanup pending",
                    PartialReparseDryRunIsolatedCleanupPlan.required("cleanup", true, true, true, true),
                    PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true),
                    true,
                    false,
                ),
            ),
        )

    val cleaned = failed.cleanupCompleted("cleanup done")

    assertTrue(failed.acquisitionAttempted)
    assertTrue(failed.acquisitionFailed)
    assertTrue(failed.isAcquisitionFailed)
    assertEquals(
        PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult.State.CLEANED_UP,
        cleaned.state,
    )
    assertTrue(cleaned.acquisition.isCleanedUp)
    assertTrue(cleaned.isCleanedUp)
  }
}
