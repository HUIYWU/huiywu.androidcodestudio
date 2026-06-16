package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCompilerReferenceTest {

  @Test
  fun notAvailableReferenceIsConservative() {
    val reference = PartialReparseDryRunIsolatedCompilerReference.notAvailable("missing")

    assertEquals(PartialReparseDryRunIsolatedCompilerReference.State.NOT_AVAILABLE, reference.state)
    assertEquals("missing", reference.reason)
    assertFalse(reference.hasCompilerReference)
    assertNull(reference.compiler)
    assertFalse(reference.requiresDestroy)
    assertFalse(reference.requiresClose)
    assertFalse(reference.isCreated)
    assertFalse(reference.isReleased)
  }

  @Test
  fun createdWithoutReferencePreservesNonExecutingState() {
    val reference =
        PartialReparseDryRunIsolatedCompilerReference.createdWithoutReference(
            "reference deferred",
            true,
            true,
        )

    assertEquals(PartialReparseDryRunIsolatedCompilerReference.State.CREATED, reference.state)
    assertEquals("reference deferred", reference.reason)
    assertFalse(reference.hasCompilerReference)
    assertNull(reference.compiler)
    assertTrue(reference.requiresDestroy)
    assertTrue(reference.requiresClose)
    assertTrue(reference.isCreated)
    assertFalse(reference.isReleased)
  }

  @Test
  fun createdWithoutReferenceKeepsReferenceStageReadyWithoutTouchingCompilerInitialization() {
    val reference =
        PartialReparseDryRunIsolatedCompilerReference.createdWithoutReference(
            "reference created",
            true,
            true,
        )

    assertEquals(PartialReparseDryRunIsolatedCompilerReference.State.CREATED, reference.state)
    assertEquals("reference created", reference.reason)
    assertFalse(reference.hasCompilerReference)
    assertNull(reference.compiler)
    assertTrue(reference.requiresDestroy)
    assertTrue(reference.requiresClose)
    assertTrue(reference.isCreated)
    assertFalse(reference.isReleased)
  }

  @Test
  fun releaseReturnsReleasedReferenceAndIsIdempotent() {
    val created =
        PartialReparseDryRunIsolatedCompilerReference.createdWithoutReference(
            "reference deferred",
            true,
            true,
        )

    val released = created.release("reference released")
    val releasedAgain = released.release("ignored")

    assertEquals(PartialReparseDryRunIsolatedCompilerReference.State.RELEASED, released.state)
    assertEquals("reference released", released.reason)
    assertFalse(released.hasCompilerReference)
    assertNull(released.compiler)
    assertFalse(released.requiresDestroy)
    assertFalse(released.requiresClose)
    assertFalse(released.isCreated)
    assertTrue(released.isReleased)
    assertSame(released, releasedAgain)
  }
}