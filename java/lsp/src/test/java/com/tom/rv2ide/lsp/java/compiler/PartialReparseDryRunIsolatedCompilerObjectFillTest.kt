package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCompilerObjectFillTest {

  @Test
  fun notFilledStateIsConservative() {
    val fill = PartialReparseDryRunIsolatedCompilerObjectFill.notFilled("not filled")

    assertEquals(PartialReparseDryRunIsolatedCompilerObjectFill.State.NOT_FILLED, fill.state)
    assertFalse(fill.fillAttempted)
    assertFalse(fill.fillFailed)
    assertFalse(fill.compilerSlot.hasReservedSlot)
  }

  @Test
  fun reservedStateKeepsSlotWithoutRealObject() {
    val fill =
        PartialReparseDryRunIsolatedCompilerObjectFill.reserved(
            "reserved",
            PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true),
        )

    assertEquals(PartialReparseDryRunIsolatedCompilerObjectFill.State.RESERVED, fill.state)
    assertTrue(fill.compilerSlot.hasReservedSlot)
    assertFalse(fill.compilerSlot.hasCompilerObject)
    assertFalse(fill.fillAttempted)
    assertTrue(fill.isReserved)
  }

  @Test
  fun fillFailedCanBeCleanedUp() {
    val failed =
        PartialReparseDryRunIsolatedCompilerObjectFill.fillFailed(
            "fill failed",
            PartialReparseDryRunIsolatedCompilerSlot.reserved("slot reserved", true),
        )

    val cleaned = failed.cleanupCompleted("cleanup done")

    assertTrue(failed.fillAttempted)
    assertTrue(failed.fillFailed)
    assertTrue(failed.isFillFailed)
    assertEquals(PartialReparseDryRunIsolatedCompilerObjectFill.State.CLEANED_UP, cleaned.state)
    assertTrue(cleaned.compilerSlot.isReleased)
    assertTrue(cleaned.isCleanedUp)
  }
}