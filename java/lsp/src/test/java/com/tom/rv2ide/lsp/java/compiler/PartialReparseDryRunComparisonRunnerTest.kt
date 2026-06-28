package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PartialReparseDryRunComparisonRunnerTest {

  @Test
  fun attachComparisonReturnsIncompleteWhenPartialSnapshotIsMissing() {
    val attemptReport = PartialReparseDryRunReport.notCreated()
    val fullSnapshot = snapshot(diagnostics = listOf("d"), methods = listOf("m"), sources = listOf("s"))

    val report =
        PartialReparseDryRunComparisonRunner()
            .attachComparison(attemptReport, fullSnapshot) { observedReport ->
              assertSame(attemptReport, observedReport)
              PartialReparseDryRunComparisonRunner.PartialSnapshotResult.of(
                  null,
                  "copy path unavailable",
              )
            }

    assertEquals(PartialReparseDryRunComparison.ComparisonState.INCOMPLETE, report.comparison.diagnosticsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.INCOMPLETE, report.comparison.methodPositionsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.INCOMPLETE, report.comparison.sourcePositionsComparison)
    assertEquals("partial dry-run snapshot is missing", report.comparison.reason)
    assertEquals("copy path unavailable", report.partialSnapshotReason)
  }

  @Test
  fun attachComparisonReturnsMatchWhenSnapshotsMatch() {
    val attemptReport = PartialReparseDryRunReport.fromAttemptResult(PartialReparseAttemptResult.success("ok"))
    val fullSnapshot = snapshot(diagnostics = listOf("d"), methods = listOf("m"), sources = listOf("s"))
    val partialSnapshot = snapshot(diagnostics = listOf("d"), methods = listOf("m"), sources = listOf("s"))

    val report =
        PartialReparseDryRunComparisonRunner()
            .attachComparison(attemptReport, fullSnapshot) {
              PartialReparseDryRunComparisonRunner.PartialSnapshotResult.of(partialSnapshot, null)
            }


    assertEquals(PartialReparseDryRunReport.AttemptState.SUCCESS, report.attemptState)
    assertEquals("ok", report.reason)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.MATCH, report.comparison.diagnosticsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.MATCH, report.comparison.methodPositionsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.MATCH, report.comparison.sourcePositionsComparison)
  }

  @Test
  fun attachComparisonReturnsMismatchPerDimensionWhenSnapshotsDiffer() {
    val attemptReport = PartialReparseDryRunReport.fromAttemptResult(PartialReparseAttemptResult.success("ok"))
    val fullSnapshot = snapshot(diagnostics = listOf("full-d"), methods = listOf("same-m"), sources = listOf("full-s"))
    val partialSnapshot = snapshot(diagnostics = listOf("partial-d"), methods = listOf("same-m"), sources = listOf("partial-s"))

    val report =
        PartialReparseDryRunComparisonRunner()
            .attachComparison(attemptReport, fullSnapshot) {
              PartialReparseDryRunComparisonRunner.PartialSnapshotResult.of(partialSnapshot, null)
            }

    assertEquals(PartialReparseDryRunComparison.ComparisonState.MISMATCH, report.comparison.diagnosticsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.MATCH, report.comparison.methodPositionsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.MISMATCH, report.comparison.sourcePositionsComparison)
  }

  @Test
  fun attachComparisonReturnsIncompleteWhenFullSnapshotIsMissing() {
    val attemptReport = PartialReparseDryRunReport.notCreated()
    val partialSnapshot = PartialReparseDryRunSnapshot.empty()

    val report =
        PartialReparseDryRunComparisonRunner()
            .attachComparison(attemptReport, null) {
              PartialReparseDryRunComparisonRunner.PartialSnapshotResult.of(partialSnapshot, null)
            }

    assertEquals(PartialReparseDryRunComparison.ComparisonState.INCOMPLETE, report.comparison.diagnosticsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.INCOMPLETE, report.comparison.methodPositionsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.INCOMPLETE, report.comparison.sourcePositionsComparison)
    assertEquals("full recompile snapshot is missing", report.comparison.reason)
  }

  private fun snapshot(
      diagnostics: List<String>,
      methods: List<String>,
      sources: List<String>,
  ): PartialReparseDryRunSnapshot = PartialReparseDryRunSnapshot(diagnostics, methods, sources)
}