package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunReportTest {

  @Test
  fun notCreatedReportsFullRecompileWithoutCommit() {
    val report = PartialReparseDryRunReport.notCreated()

    assertEquals(PartialReparseDryRunReport.AttemptState.NOT_CREATED, report.attemptState)
    assertNull(report.reason)
    assertBaseInvariant(report)
    assertDefaultComparisonNotRun(report)
  }

  @Test
  fun fromAttemptResultMapsSuccess() {
    val report = PartialReparseDryRunReport.fromAttemptResult(PartialReparseAttemptResult.success("ok"))

    assertEquals(PartialReparseDryRunReport.AttemptState.SUCCESS, report.attemptState)
    assertEquals("ok", report.reason)
    assertBaseInvariant(report)
    assertDefaultComparisonNotRun(report)
  }

  @Test
  fun fromAttemptResultMapsNotApplicable() {
    val report =
        PartialReparseDryRunReport.fromAttemptResult(
            PartialReparseAttemptResult.notApplicable("not applicable"))

    assertEquals(PartialReparseDryRunReport.AttemptState.NOT_APPLICABLE, report.attemptState)
    assertEquals("not applicable", report.reason)
    assertBaseInvariant(report)
    assertDefaultComparisonNotRun(report)
  }

  @Test
  fun fromAttemptResultMapsFailed() {
    val report = PartialReparseDryRunReport.fromAttemptResult(PartialReparseAttemptResult.failed("failed"))

    assertEquals(PartialReparseDryRunReport.AttemptState.FAILED, report.attemptState)
    assertEquals("failed", report.reason)
    assertBaseInvariant(report)
    assertDefaultComparisonNotRun(report)
  }

  @Test
  fun exceptionMapsThrowableMessage() {
    val report = PartialReparseDryRunReport.exception(IllegalStateException("boom"))

    assertEquals(PartialReparseDryRunReport.AttemptState.EXCEPTION, report.attemptState)
    assertEquals("boom", report.reason)
    assertBaseInvariant(report)
    assertDefaultComparisonNotRun(report)
  }

  @Test
  fun withComparisonKeepsAttemptFieldsAndReplacesComparison() {
    val comparison = PartialReparseDryRunComparison.incomplete("missing snapshot")

    val report = PartialReparseDryRunReport.fromAttemptResult(PartialReparseAttemptResult.success("ok"))
    val updated = report.withComparison(comparison)

    assertEquals(PartialReparseDryRunReport.AttemptState.SUCCESS, updated.attemptState)
    assertEquals("ok", updated.reason)
    assertBaseInvariant(updated)
    assertSame(comparison, updated.comparison)
  }

  private fun assertBaseInvariant(report: PartialReparseDryRunReport) {
    assertTrue(report.fullRecompileExecuted)
    assertFalse(report.partialResultCommitted)
  }

  private fun assertDefaultComparisonNotRun(report: PartialReparseDryRunReport) {
    assertEquals(PartialReparseDryRunComparison.ComparisonState.NOT_RUN, report.comparison.diagnosticsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.NOT_RUN, report.comparison.methodPositionsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.NOT_RUN, report.comparison.sourcePositionsComparison)
    assertNull(report.comparison.reason)
  }
}