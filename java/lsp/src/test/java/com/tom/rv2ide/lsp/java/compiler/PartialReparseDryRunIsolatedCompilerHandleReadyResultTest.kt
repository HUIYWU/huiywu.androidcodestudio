package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCompilerHandleReadyResultTest {

  @Test
  fun notReadyStateIsConservative() {
    val result = PartialReparseDryRunIsolatedCompilerHandleReadyResult.notReady("not ready")

    assertEquals(
        PartialReparseDryRunIsolatedCompilerHandleReadyResult.State.NOT_READY,
        result.state,
    )
    assertFalse(result.readyCheckAttempted)
    assertFalse(result.readyFailed)
    assertFalse(result.handle.isCreated)
  }

  @Test
  fun deferredStateKeepsHandleCreatedButNotReady() {
    val handle =
        PartialReparseDryRunIsolatedCompilerHandle.created(
            "handle created",
            true,
            true,
            true,
            true,
            true,
        )

    val result =
        PartialReparseDryRunIsolatedCompilerHandleReadyResult.deferred(
            "handle deferred",
            handle,
        )

    assertEquals(
        PartialReparseDryRunIsolatedCompilerHandleReadyResult.State.DEFERRED,
        result.state,
    )
    assertFalse(result.readyCheckAttempted)
    assertFalse(result.readyFailed)
    assertTrue(result.handle.isCreated)
    assertTrue(result.isDeferred)
  }

  @Test
  fun readyFailedCanBeCleanedUp() {
    val handle =
        PartialReparseDryRunIsolatedCompilerHandle.created(
            "handle created",
            true,
            true,
            true,
            true,
            true,
        )

    val failed =
        PartialReparseDryRunIsolatedCompilerHandleReadyResult.readyFailed(
            "ready failed",
            handle,
        )

    val cleaned = failed.cleanupCompleted("cleanup done")

    assertTrue(failed.readyCheckAttempted)
    assertTrue(failed.readyFailed)
    assertTrue(failed.isReadyFailed)
    assertEquals(
        PartialReparseDryRunIsolatedCompilerHandleReadyResult.State.CLEANED_UP,
        cleaned.state,
    )
    assertTrue(cleaned.handle.isReleased)
    assertTrue(cleaned.isCleanedUp)
  }
}