package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCompilerCreationResultTest {

  @Test
  fun notCreatedResultCarriesReason() {
    val result = PartialReparseDryRunIsolatedCompilerCreationResult.notCreated("not created")

    assertEquals(PartialReparseDryRunIsolatedCompilerCreationResult.State.NOT_CREATED, result.state)
    assertEquals("not created", result.reason)
    assertFalse(result.hasCreatedCompiler)
    assertFalse(result.requiresDestroy)
    assertFalse(result.requiresClose)
    assertFalse(result.cleanupRequired)
    assertFalse(result.cleanupCompleted)
    assertFalse(result.isCreated)
  }

  @Test
  fun createdWithoutCompilerKeepsCreationStageReady() {
    val result =
        PartialReparseDryRunIsolatedCompilerCreationResult.createdWithoutCompiler(
            "created without compiler",
            true,
            true,
        )

    assertEquals(PartialReparseDryRunIsolatedCompilerCreationResult.State.CREATED, result.state)
    assertEquals("created without compiler", result.reason)
    assertFalse(result.hasCreatedCompiler)
    assertTrue(result.requiresDestroy)
    assertTrue(result.requiresClose)
    assertFalse(result.cleanupRequired)
    assertFalse(result.cleanupCompleted)
    assertTrue(result.isCreated)
  }

  @Test
  fun failedResultCanRequireCleanup() {
    val result = PartialReparseDryRunIsolatedCompilerCreationResult.failed("copy failed", true)

    assertEquals(PartialReparseDryRunIsolatedCompilerCreationResult.State.FAILED, result.state)
    assertEquals("copy failed", result.reason)
    assertFalse(result.hasCreatedCompiler)
    assertTrue(result.cleanupRequired)
    assertFalse(result.cleanupCompleted)
    assertTrue(result.isFailed)
  }

  @Test
  fun cleanupCompletedIsIdempotent() {
    val failed = PartialReparseDryRunIsolatedCompilerCreationResult.failed("copy failed", true)

    val cleaned = failed.cleanupCompleted("cleanup done")
    val cleanedAgain = cleaned.cleanupCompleted("cleanup done again")

    assertEquals(PartialReparseDryRunIsolatedCompilerCreationResult.State.CLEANED_UP, cleaned.state)
    assertEquals("cleanup done", cleaned.reason)
    assertFalse(cleaned.cleanupRequired)
    assertTrue(cleaned.cleanupCompleted)
    assertTrue(cleaned.isCleanedUp)
    assertSame(cleaned, cleanedAgain)
  }
}
