package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCompilerObjectAttachResultTest {

  @Test
  fun notAttachedStateIsConservative() {
    val result = PartialReparseDryRunIsolatedCompilerObjectAttachResult.notAttached("not attached")

    assertEquals(
        PartialReparseDryRunIsolatedCompilerObjectAttachResult.State.NOT_ATTACHED,
        result.state,
    )
    assertFalse(result.attachAttempted)
    assertFalse(result.attachFailed)
    assertFalse(result.reference.isCreated)
  }

  @Test
  fun deferredStateKeepsReferenceStageReadyWithoutRealAttach() {
    val fill =
        PartialReparseDryRunIsolatedCompilerObjectFill.reserved(
            "reserved",
            PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true),
        )

    val result =
        PartialReparseDryRunIsolatedCompilerObjectAttachResult.deferred(
            "attach deferred",
            fill,
        )

    assertEquals(
        PartialReparseDryRunIsolatedCompilerObjectAttachResult.State.DEFERRED,
        result.state,
    )
    assertFalse(result.attachAttempted)
    assertFalse(result.attachFailed)
    assertTrue(result.reference.isCreated)
    assertTrue(result.isDeferred)
  }

  @Test
  fun attachFailedCanBeCleanedUp() {
    val failed =
        PartialReparseDryRunIsolatedCompilerObjectAttachResult.attachFailed(
            "attach failed",
            PartialReparseDryRunIsolatedCompilerObjectFill.fillFailed(
                "fill failed",
                PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true),
            ),
        )

    val cleaned = failed.cleanupCompleted("cleanup done")

    assertTrue(failed.attachAttempted)
    assertTrue(failed.attachFailed)
    assertTrue(failed.isAttachFailed)
    assertEquals(
        PartialReparseDryRunIsolatedCompilerObjectAttachResult.State.CLEANED_UP,
        cleaned.state,
    )
    assertTrue(cleaned.objectFill.isCleanedUp)
    assertTrue(cleaned.reference.isReleased)
    assertTrue(cleaned.isCleanedUp)
  }
}