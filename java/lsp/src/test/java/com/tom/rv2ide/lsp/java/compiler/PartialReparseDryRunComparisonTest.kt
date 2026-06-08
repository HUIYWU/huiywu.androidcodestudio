package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartialReparseDryRunComparisonTest {

  @Test
  fun notRunUsesNotRunForAllDimensions() {
    val comparison = PartialReparseDryRunComparison.notRun()

    assertEquals(PartialReparseDryRunComparison.ComparisonState.NOT_RUN, comparison.diagnosticsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.NOT_RUN, comparison.methodPositionsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.NOT_RUN, comparison.sourcePositionsComparison)
    assertNull(comparison.reason)
  }

  @Test
  fun incompleteUsesIncompleteForAllDimensions() {
    val comparison = PartialReparseDryRunComparison.incomplete("missing snapshot")

    assertEquals(PartialReparseDryRunComparison.ComparisonState.INCOMPLETE, comparison.diagnosticsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.INCOMPLETE, comparison.methodPositionsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.INCOMPLETE, comparison.sourcePositionsComparison)
    assertEquals("missing snapshot", comparison.reason)
  }

  @Test
  fun fromStatesKeepsEachDimension() {
    val comparison =
        PartialReparseDryRunComparison.fromStates(
            PartialReparseDryRunComparison.ComparisonState.MATCH,
            PartialReparseDryRunComparison.ComparisonState.MISMATCH,
            PartialReparseDryRunComparison.ComparisonState.INCOMPLETE,
            "mixed")

    assertEquals(PartialReparseDryRunComparison.ComparisonState.MATCH, comparison.diagnosticsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.MISMATCH, comparison.methodPositionsComparison)
    assertEquals(PartialReparseDryRunComparison.ComparisonState.INCOMPLETE, comparison.sourcePositionsComparison)
    assertEquals("mixed", comparison.reason)
  }
}