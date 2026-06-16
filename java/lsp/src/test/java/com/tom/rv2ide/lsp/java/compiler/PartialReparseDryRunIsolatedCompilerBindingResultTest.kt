package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCompilerBindingResultTest {

  @Test
  fun notBoundStateIsConservative() {
    val result = PartialReparseDryRunIsolatedCompilerBindingResult.notBound("not bound")

    assertEquals(PartialReparseDryRunIsolatedCompilerBindingResult.State.NOT_BOUND, result.state)
    assertFalse(result.bindingAttempted)
    assertFalse(result.hasBoundCompilerObject)
    assertFalse(result.reference.isCreated)
  }

  @Test
  fun deferredStateKeepsReferenceStageReadyWithoutObjectBinding() {
    val acquisition =
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
        )
    val reference =
        PartialReparseDryRunIsolatedCompilerReference.createdWithoutReference("deferred", true, true)

    val result =
        PartialReparseDryRunIsolatedCompilerBindingResult.deferred(
            "deferred binding",
            acquisition,
            reference,
        )

    assertEquals(PartialReparseDryRunIsolatedCompilerBindingResult.State.DEFERRED, result.state)
    assertFalse(result.bindingAttempted)
    assertFalse(result.hasBoundCompilerObject)
    assertTrue(result.reference.isCreated)
    assertTrue(result.isDeferred)
  }

  @Test
  fun bindingFailedCanBeCleanedUp() {
    val acquisition =
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
        )
    val result =
        PartialReparseDryRunIsolatedCompilerBindingResult.bindingFailed(
            "binding failed",
            acquisition,
            PartialReparseDryRunIsolatedCompilerReference.notAvailable("binding failed"),
        )

    val cleaned = result.cleanupCompleted("cleanup done")

    assertTrue(result.bindingAttempted)
    assertFalse(result.hasBoundCompilerObject)
    assertTrue(result.isBindingFailed)
    assertEquals(PartialReparseDryRunIsolatedCompilerBindingResult.State.CLEANED_UP, cleaned.state)
    assertTrue(cleaned.acquisition.isCleanedUp)
    assertTrue(cleaned.reference.isReleased)
    assertTrue(cleaned.isCleanedUp)
  }
}