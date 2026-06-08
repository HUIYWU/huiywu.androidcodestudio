package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PartialReparseFallbackHandlerTest {

  @Test
  fun successDoesNotFallback() {
    val calls = Calls()
    val result = PartialReparseAttemptResult.success("method body reparsed")

    val outcome =
        PartialReparseFallbackHandler()
            .handle(
                { result },
                { calls.fullRecompile++ },
                { fallbackResult, error ->
                  calls.fallbackObserved++
                  calls.observedResult = fallbackResult
                  calls.observedError = error
                },
            )

    assertEquals(PartialReparseFallbackHandler.Outcome.SUCCESS, outcome)
    assertEquals(0, calls.fullRecompile)
    assertEquals(0, calls.fallbackObserved)
    assertNull(calls.observedResult)
    assertNull(calls.observedError)
  }

  @Test
  fun notApplicableFallsBack() {
    val calls = Calls()
    val result = PartialReparseAttemptResult.notApplicable("method positions not found")

    val outcome =
        PartialReparseFallbackHandler()
            .handle(
                { result },
                { calls.fullRecompile++ },
                { fallbackResult, error ->
                  calls.fallbackObserved++
                  calls.observedResult = fallbackResult
                  calls.observedError = error
                },
            )

    assertEquals(PartialReparseFallbackHandler.Outcome.FALLBACK_AFTER_NOT_APPLICABLE, outcome)
    assertEquals(1, calls.fullRecompile)
    assertEquals(1, calls.fallbackObserved)
    assertSame(result, calls.observedResult)
    assertNull(calls.observedError)
  }

  @Test
  fun failedFallsBack() {
    val calls = Calls()
    val result = PartialReparseAttemptResult.failed("PartialReparser.reparseMethod returned false")

    val outcome =
        PartialReparseFallbackHandler()
            .handle(
                { result },
                { calls.fullRecompile++ },
                { fallbackResult, error ->
                  calls.fallbackObserved++
                  calls.observedResult = fallbackResult
                  calls.observedError = error
                },
            )

    assertEquals(PartialReparseFallbackHandler.Outcome.FALLBACK_AFTER_FAILED, outcome)
    assertEquals(1, calls.fullRecompile)
    assertEquals(1, calls.fallbackObserved)
    assertSame(result, calls.observedResult)
    assertNull(calls.observedError)
  }

  @Test
  fun exceptionFallsBack() {
    val calls = Calls()
    val error = IllegalStateException("boom")

    val outcome =
        PartialReparseFallbackHandler()
            .handle(
                { throw error },
                { calls.fullRecompile++ },
                { fallbackResult, observedError ->
                  calls.fallbackObserved++
                  calls.observedResult = fallbackResult
                  calls.observedError = observedError
                },
            )

    assertEquals(PartialReparseFallbackHandler.Outcome.FALLBACK_AFTER_EXCEPTION, outcome)
    assertEquals(1, calls.fullRecompile)
    assertEquals(1, calls.fallbackObserved)
    assertNull(calls.observedResult)
    assertSame(error, calls.observedError)
  }

  private class Calls {
    var fullRecompile = 0
    var fallbackObserved = 0
    var observedResult: PartialReparseAttemptResult? = null
    var observedError: Throwable? = null
  }
}