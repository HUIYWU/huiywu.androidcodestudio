package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCompilerObjectMaterializationTest {

  @Test
  fun notMaterializedStateIsConservative() {
    val result =
        PartialReparseDryRunIsolatedCompilerObjectMaterialization.notMaterialized("not materialized")

    assertEquals(
        PartialReparseDryRunIsolatedCompilerObjectMaterialization.State.NOT_MATERIALIZED,
        result.state,
    )
    assertFalse(result.materializationAttempted)
    assertFalse(result.materializationFailed)
    assertFalse(result.objectAcquisitionResult.acquisition.compilerSlot.hasReservedSlot)
  }

  @Test
  fun reservedStateKeepsMaterializationDeferred() {
    val result =
        PartialReparseDryRunIsolatedCompilerObjectMaterialization.reserved(
            "reserved",
            PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult.reserved(
                "acquisition reserved",
                PartialReparseDryRunIsolatedCompilerAcquisition.reserved(
                    "lifecycle reserved",
                    PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true),
                    PartialReparseDryRunIsolatedCleanupExecutor.pending(
                        "cleanup pending",
                        PartialReparseDryRunIsolatedCleanupPlan.required(
                            "cleanup",
                            true,
                            true,
                            true,
                            true,
                        ),
                        PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true),
                        true,
                        false,
                    ),
                ),
            ),
        )

    assertEquals(
        PartialReparseDryRunIsolatedCompilerObjectMaterialization.State.RESERVED,
        result.state,
    )
    assertFalse(result.materializationAttempted)
    assertFalse(result.materializationFailed)
    assertTrue(result.objectAcquisitionResult.isReserved)
    assertTrue(result.isReserved)
  }

  @Test
  fun materializationFailedCanBeCleanedUp() {
    val failed =
        PartialReparseDryRunIsolatedCompilerObjectMaterialization.materializationFailed(
            "materialization failed",
            PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult.acquisitionFailed(
                "acquisition failed",
                PartialReparseDryRunIsolatedCompilerAcquisition.failed(
                    "lifecycle failed",
                    PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true),
                    PartialReparseDryRunIsolatedCleanupExecutor.pending(
                        "cleanup pending",
                        PartialReparseDryRunIsolatedCleanupPlan.required(
                            "cleanup",
                            true,
                            true,
                            true,
                            true,
                        ),
                        PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true),
                        true,
                        false,
                    ),
                ),
            ),
        )

    val cleaned = failed.cleanupCompleted("cleanup done")

    assertTrue(failed.materializationAttempted)
    assertTrue(failed.materializationFailed)
    assertTrue(failed.isMaterializationFailed)
    assertEquals(
        PartialReparseDryRunIsolatedCompilerObjectMaterialization.State.CLEANED_UP,
        cleaned.state,
    )
    assertTrue(cleaned.objectAcquisitionResult.isCleanedUp)
    assertTrue(cleaned.isCleanedUp)
  }
}