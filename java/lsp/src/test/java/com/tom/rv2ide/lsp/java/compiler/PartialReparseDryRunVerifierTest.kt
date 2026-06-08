package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunVerifierTest {

  @Test
  fun verifyThenFullRecompileReportsNotCreatedWhenAttemptIsNull() {
    val calls = Calls()

    val report =
        PartialReparseDryRunVerifier()
            .verifyThenFullRecompile(
                null,
                { calls.fullRecompile++ },
                { result, error -> calls.observe(result, error) },
            )

    assertReport(
        report,
        PartialReparseDryRunReport.AttemptState.NOT_CREATED,
        reason = null,
    )
    assertEquals(1, calls.fullRecompile)
    assertEquals(0, calls.observed)
  }

  @Test
  fun verifyThenFullRecompileReportsSuccessButStillFullRecompiles() {
    val calls = Calls()
    val result = PartialReparseAttemptResult.success("ok")

    val report =
        PartialReparseDryRunVerifier()
            .verifyThenFullRecompile(
                { result },
                { calls.fullRecompile++ },
                { observedResult, error -> calls.observe(observedResult, error) },
            )

    assertReport(report, PartialReparseDryRunReport.AttemptState.SUCCESS, "ok")
    assertEquals(1, calls.fullRecompile)
    assertEquals(1, calls.observed)
    assertSame(result, calls.observedResult)
    assertNull(calls.observedError)
  }

  @Test
  fun verifyThenFullRecompileReportsNotApplicableButStillFullRecompiles() {
    val calls = Calls()
    val result = PartialReparseAttemptResult.notApplicable("not applicable")

    val report =
        PartialReparseDryRunVerifier()
            .verifyThenFullRecompile(
                { result },
                { calls.fullRecompile++ },
                { observedResult, error -> calls.observe(observedResult, error) },
            )

    assertReport(report, PartialReparseDryRunReport.AttemptState.NOT_APPLICABLE, "not applicable")
    assertEquals(1, calls.fullRecompile)
    assertEquals(1, calls.observed)
    assertSame(result, calls.observedResult)
    assertNull(calls.observedError)
  }

  @Test
  fun verifyThenFullRecompileReportsFailedButStillFullRecompiles() {
    val calls = Calls()
    val result = PartialReparseAttemptResult.failed("failed")

    val report =
        PartialReparseDryRunVerifier()
            .verifyThenFullRecompile(
                { result },
                { calls.fullRecompile++ },
                { observedResult, error -> calls.observe(observedResult, error) },
            )

    assertReport(report, PartialReparseDryRunReport.AttemptState.FAILED, "failed")
    assertEquals(1, calls.fullRecompile)
    assertEquals(1, calls.observed)
    assertSame(result, calls.observedResult)
    assertNull(calls.observedError)
  }

  @Test
  fun verifyThenFullRecompileReportsExceptionButStillFullRecompiles() {
    val calls = Calls()
    val error = IllegalStateException("boom")

    val report =
        PartialReparseDryRunVerifier()
            .verifyThenFullRecompile(
                { throw error },
                { calls.fullRecompile++ },
                { result, observedError -> calls.observe(result, observedError) },
            )

    assertReport(report, PartialReparseDryRunReport.AttemptState.EXCEPTION, "boom")
    assertEquals(1, calls.fullRecompile)
    assertEquals(1, calls.observed)
    assertNull(calls.observedResult)
    assertSame(error, calls.observedError)
  }

  private fun assertReport(
      report: PartialReparseDryRunReport,
      state: PartialReparseDryRunReport.AttemptState,
      reason: String?,
  ) {
    assertEquals(state, report.attemptState)
    assertEquals(reason, report.reason)
    assertTrue(report.fullRecompileExecuted)
    assertFalse(report.partialResultCommitted)
  }

  private class Calls {
    var fullRecompile = 0
    var observed = 0
    var observedResult: PartialReparseAttemptResult? = null
    var observedError: Throwable? = null

    fun observe(result: PartialReparseAttemptResult?, error: Throwable?) {
      observed++
      observedResult = result
      observedError = error
    }
  }
}