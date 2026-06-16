package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCompilerReferenceReadyResultTest {

  @Test
  fun notReadyStateIsConservative() {
    val result = PartialReparseDryRunIsolatedCompilerReferenceReadyResult.notReady("not ready")

    assertEquals(
        PartialReparseDryRunIsolatedCompilerReferenceReadyResult.State.NOT_READY,
        result.state,
    )
    assertFalse(result.readyCheckAttempted)
    assertFalse(result.readyFailed)
    assertFalse(result.reference.isCreated)
  }

  @Test
  fun deferredStateKeepsReferenceCreatedButNotReady() {
    val bindingResult =
        PartialReparseDryRunIsolatedCompilerBindingResult.deferred(
            "binding deferred",
            PartialReparseDryRunIsolatedCompilerAcquisition.reserved(
                "reserved",
                PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true),
                PartialReparseDryRunIsolatedCleanupExecutor.pending(
                    "cleanup pending",
                    PartialReparseDryRunIsolatedCleanupPlan.required("cleanup", true, true, true, true),
                    PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true),
                    true,
                    false,
                ),
            ),
            PartialReparseDryRunIsolatedCompilerReference.createdWithoutReference(
                "reference deferred",
                true,
                true,
            ),
        )

    val result =
        PartialReparseDryRunIsolatedCompilerReferenceReadyResult.deferred(
            "reference deferred",
            bindingResult,
            bindingResult.reference,
        )

    assertEquals(
        PartialReparseDryRunIsolatedCompilerReferenceReadyResult.State.DEFERRED,
        result.state,
    )
    assertFalse(result.readyCheckAttempted)
    assertFalse(result.readyFailed)
    assertTrue(result.reference.isCreated)
    assertTrue(result.isDeferred)
  }

  @Test
  fun readyFailedCanBeCleanedUp() {
    val bindingResult =
        PartialReparseDryRunIsolatedCompilerBindingResult.bindingFailed(
            "binding failed",
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
            PartialReparseDryRunIsolatedCompilerReference.notAvailable("binding failed"),
        )

    val failed =
        PartialReparseDryRunIsolatedCompilerReferenceReadyResult.readyFailed(
            "ready failed",
            bindingResult,
            bindingResult.reference,
        )

    val cleaned = failed.cleanupCompleted("cleanup done")

    assertTrue(failed.readyCheckAttempted)
    assertTrue(failed.readyFailed)
    assertTrue(failed.isReadyFailed)
    assertEquals(
        PartialReparseDryRunIsolatedCompilerReferenceReadyResult.State.CLEANED_UP,
        cleaned.state,
    )
    assertTrue(cleaned.bindingResult.isCleanedUp)
    assertTrue(cleaned.reference.isReleased)
    assertTrue(cleaned.isCleanedUp)
  }
}