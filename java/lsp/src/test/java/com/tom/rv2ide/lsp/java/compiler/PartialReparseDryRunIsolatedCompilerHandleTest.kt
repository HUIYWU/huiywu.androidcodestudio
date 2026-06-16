package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCompilerHandleTest {

  @Test
  fun notAvailableHandleIsConservative() {
    val handle = PartialReparseDryRunIsolatedCompilerHandle.notAvailable("missing")

    assertEquals(PartialReparseDryRunIsolatedCompilerHandle.State.NOT_AVAILABLE, handle.state)
    assertEquals("missing", handle.reason)
    assertFalse(handle.hasCompilerCopy)
    assertFalse(handle.requiresDestroy)
    assertFalse(handle.requiresClose)
    assertFalse(handle.cleanupPlan.isRequired)
    assertFalse(handle.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(handle.requiresFreshReusableCompiler)
    assertTrue(handle.cachedCompileMustStartEmpty)
    assertFalse(handle.isCreated)
    assertFalse(handle.isReleased)
  }

  @Test
  fun createdHandleCarriesResourceOwnershipContract() {
    val handle =
        PartialReparseDryRunIsolatedCompilerHandle.created(
            "handle created",
            true,
            true,
            true,
            true,
            true,
        )

    assertEquals(PartialReparseDryRunIsolatedCompilerHandle.State.CREATED, handle.state)
    assertEquals("handle created", handle.reason)
    assertTrue(handle.hasCompilerCopy)
    assertTrue(handle.requiresDestroy)
    assertTrue(handle.requiresClose)
    assertTrue(handle.cleanupPlan.isRequired)
    assertTrue(handle.cleanupPlan.cleanupOwnedBySession)
    assertTrue(handle.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(handle.requiresFreshReusableCompiler)
    assertTrue(handle.cachedCompileMustStartEmpty)
    assertTrue(handle.isCreated)
    assertFalse(handle.isReleased)
  }

  @Test
  fun releaseReturnsReleasedHandleAndIsIdempotent() {
    val created =
        PartialReparseDryRunIsolatedCompilerHandle.created(
            "handle created",
            true,
            true,
            true,
            true,
            true,
        )

    val released = created.release("handle released")
    val releasedAgain = released.release("ignored")

    assertEquals(PartialReparseDryRunIsolatedCompilerHandle.State.RELEASED, released.state)
    assertEquals("handle released", released.reason)
    assertFalse(released.hasCompilerCopy)
    assertFalse(released.requiresDestroy)
    assertFalse(released.requiresClose)
    assertTrue(released.cleanupPlan.isCompleted)
    assertTrue(released.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(released.requiresFreshReusableCompiler)
    assertTrue(released.cachedCompileMustStartEmpty)
    assertFalse(released.isCreated)
    assertTrue(released.isReleased)
    assertSame(released, releasedAgain)
  }
}