package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunSnapshotTest {

  @Test
  fun constructorCopiesInputLists() {
    val diagnostics = mutableListOf("diag-1")
    val methods = mutableListOf("method-1")
    val sources = mutableListOf("source-1")

    val snapshot = PartialReparseDryRunSnapshot(diagnostics, methods, sources)
    diagnostics += "diag-2"
    methods += "method-2"
    sources += "source-2"

    assertEquals(listOf("diag-1"), snapshot.diagnostics)
    assertEquals(listOf("method-1"), snapshot.methodPositionKeys)
    assertEquals(listOf("source-1"), snapshot.sourcePositionKeys)
  }

  @Test(expected = UnsupportedOperationException::class)
  fun diagnosticsAreImmutable() {
    val snapshot = PartialReparseDryRunSnapshot.empty()

    @Suppress("UNCHECKED_CAST")
    (snapshot.diagnostics as MutableList<String>).add("diag")
  }

  @Test
  fun emptySnapshotHasEmptyLists() {
    val snapshot = PartialReparseDryRunSnapshot.empty()

    assertTrue(snapshot.diagnostics.isEmpty())
    assertTrue(snapshot.methodPositionKeys.isEmpty())
    assertTrue(snapshot.sourcePositionKeys.isEmpty())
  }
}