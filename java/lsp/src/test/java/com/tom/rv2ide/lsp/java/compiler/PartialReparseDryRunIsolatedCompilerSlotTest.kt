package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCompilerSlotTest {

  @Test
  fun notAllocatedSlotIsConservative() {
    val slot = PartialReparseDryRunIsolatedCompilerSlot.notAllocated("none")

    assertEquals(PartialReparseDryRunIsolatedCompilerSlot.State.NOT_ALLOCATED, slot.state)
    assertFalse(slot.hasReservedSlot)
    assertFalse(slot.hasCompilerObject)
    assertFalse(slot.ownedBySession)
    assertFalse(slot.isReserved)
    assertFalse(slot.isFilled)
  }

  @Test
  fun reservedSlotCanModelObjectOwnershipWithoutRealCompiler() {
    val slot = PartialReparseDryRunIsolatedCompilerSlot.reserved("reserved", true)

    assertEquals(PartialReparseDryRunIsolatedCompilerSlot.State.RESERVED, slot.state)
    assertTrue(slot.hasReservedSlot)
    assertFalse(slot.hasCompilerObject)
    assertTrue(slot.ownedBySession)
    assertTrue(slot.isReserved)
    assertFalse(slot.isFilled)
  }

  @Test
  fun releaseIsIdempotent() {
    val slot = PartialReparseDryRunIsolatedCompilerSlot.reserved("reserved", true)

    val released = slot.release("released")
    val releasedAgain = released.release("released again")

    assertEquals(PartialReparseDryRunIsolatedCompilerSlot.State.RELEASED, released.state)
    assertFalse(released.hasReservedSlot)
    assertFalse(released.hasCompilerObject)
    assertFalse(released.ownedBySession)
    assertTrue(released.isReleased)
    assertSame(released, releasedAgain)
  }
}