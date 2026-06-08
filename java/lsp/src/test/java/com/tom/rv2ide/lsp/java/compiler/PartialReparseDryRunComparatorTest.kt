package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Test

class PartialReparseDryRunComparatorTest {

  @Test
  fun compareReturnsMatchForIdenticalSnapshots() {
    val snapshot = snapshot(diagnostics = listOf("d1"), methods = listOf("m1"), sources = listOf("s1"))

    val comparison = PartialReparseDryRunComparator().compare(snapshot, snapshot)

    assertEquals(PartialReparseDryRunComparison.ComparisonState.MATCH, comparison.diagnosticsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.MATCH, comparison.methodPositionsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.MATCH, comparison.sourcePositionsComparison)
  }

  @Test
  fun compareReturnsMismatchPerDifferentDimension() {
    val partial = snapshot(diagnostics = listOf("partial-d"), methods = listOf("same-m"), sources = listOf("partial-s"))
    val full = snapshot(diagnostics = listOf("full-d"), methods = listOf("same-m"), sources = listOf("full-s"))

    val comparison = PartialReparseDryRunComparator().compare(partial, full)

    assertEquals(PartialReparseDryRunComparison.ComparisonState.MISMATCH, comparison.diagnosticsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.MATCH, comparison.methodPositionsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.MISMATCH, comparison.sourcePositionsComparison)
  }

  @Test
  fun compareReturnsIncompleteWhenPartialSnapshotIsMissing() {
    val comparison = PartialReparseDryRunComparator().compare(null, PartialReparseDryRunSnapshot.empty())

    assertEquals(PartialReparseDryRunComparison.ComparisonState.INCOMPLETE, comparison.diagnosticsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.INCOMPLETE, comparison.methodPositionsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.INCOMPLETE, comparison.sourcePositionsComparison)
    assertEquals("partial dry-run snapshot is missing", comparison.reason)
  }

  @Test
  fun compareReturnsIncompleteWhenFullSnapshotIsMissing() {
    val comparison = PartialReparseDryRunComparator().compare(PartialReparseDryRunSnapshot.empty(), null)

    assertEquals(PartialReparseDryRunComparison.ComparisonState.INCOMPLETE, comparison.diagnosticsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.INCOMPLETE, comparison.methodPositionsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.INCOMPLETE, comparison.sourcePositionsComparison)
    assertEquals("full recompile snapshot is missing", comparison.reason)
  }

  private fun snapshot(
      diagnostics: List<String>,
      methods: List<String>,
      sources: List<String>,
  ): PartialReparseDryRunSnapshot = PartialReparseDryRunSnapshot(diagnostics, methods, sources)
}