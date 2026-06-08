package com.tom.rv2ide.lsp.java.compiler

import androidx.core.util.Pair
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import java.util.Locale
import jdkx.tools.Diagnostic
import jdkx.tools.JavaFileObject
import openjdk.source.util.TreePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunSnapshotCollectorTest {

  @Test
  fun collectReturnsEmptySnapshotForNullInputs() {
    val snapshot = PartialReparseDryRunSnapshotCollector().collect(null, null)

    assertTrue(snapshot.diagnostics.isEmpty())
    assertTrue(snapshot.methodPositionKeys.isEmpty())
    assertTrue(snapshot.sourcePositionKeys.isEmpty())
  }

  @Test
  fun collectDiagnosticKeysSortsAndNormalizesDiagnostics() {
    val diagnostics =
        listOf(
            diagnostic(code = "z.code", line = 3, column = 4, start = 30, end = 40, message = "z message"),
            diagnostic(code = "a.code", line = 1, column = 2, start = 10, end = 20, message = "a message"),
        )

    val keys = PartialReparseDryRunSnapshotCollector().collectDiagnosticKeys(diagnostics)

    assertEquals(
        listOf(
            "<no-source>|ERROR|a.code|line=1|column=2|start=10|end=20|message=a message",
            "<no-source>|ERROR|z.code|line=3|column=4|start=30|end=40|message=z message",
        ),
        keys,
    )
  }

  @Test
  fun collectDiagnosticKeysHandlesNullDiagnostic() {
    val keys = PartialReparseDryRunSnapshotCollector().collectDiagnosticKeys(listOf(null))

    assertEquals(listOf("<null-diagnostic>"), keys)
  }

  @Test
  fun collectMethodPositionKeysIncludesPathAndSortedRanges() {
    val methodPositions =
        mapOf(
            "/b.java" to listOf(rangePair(range(2, 3, 20, 4, 5, 40))),
            "/a.java" to listOf(rangePair(range(1, 2, 10, 1, 8, 18))),
        )

    val keys = PartialReparseDryRunSnapshotCollector().collectMethodPositionKeys(methodPositions)

    assertEquals(
        listOf(
            "/a.java|1:2:10-1:8:18",
            "/b.java|2:3:20-4:5:40",
        ),
        keys,
    )
  }

  @Test
  fun collectSourcePositionKeysUsesRangesWithoutPath() {
    val methodPositions =
        mapOf(
            "/b.java" to listOf(rangePair(range(2, 3, 20, 4, 5, 40))),
            "/a.java" to listOf(rangePair(range(1, 2, 10, 1, 8, 18))),
        )

    val keys = PartialReparseDryRunSnapshotCollector().collectSourcePositionKeys(methodPositions)

    assertEquals(listOf("1:2:10-1:8:18", "2:3:20-4:5:40"), keys)
  }

  @Test
  fun collectSkipsNullRangesAndNullLists() {
    val methodPositions: Map<String, List<Pair<Range, TreePath>>?> =
        mapOf(
            "/null-list.java" to null,
            "/null-range.java" to listOf(Pair(null, null)),
            "/ok.java" to listOf(rangePair(range(0, 1, 2, 0, 3, 4))),
        )

    @Suppress("UNCHECKED_CAST")
    val snapshot =
        PartialReparseDryRunSnapshotCollector()
            .collect(emptyList(), methodPositions as Map<String, List<Pair<Range, TreePath>>>)

    assertEquals(listOf("/ok.java|0:1:2-0:3:4"), snapshot.methodPositionKeys)
    assertEquals(listOf("0:1:2-0:3:4"), snapshot.sourcePositionKeys)
  }

  private fun rangePair(range: Range): Pair<Range, TreePath> = Pair(range, null)

  private fun range(
      startLine: Int,
      startColumn: Int,
      startIndex: Int,
      endLine: Int,
      endColumn: Int,
      endIndex: Int,
  ): Range = Range(Position(startLine, startColumn, startIndex), Position(endLine, endColumn, endIndex))

  private fun diagnostic(
      code: String,
      line: Long,
      column: Long,
      start: Long,
      end: Long,
      message: String,
  ): Diagnostic<JavaFileObject> = FakeDiagnostic(code, line, column, start, end, message)

  private class FakeDiagnostic(
      private val codeValue: String,
      private val line: Long,
      private val column: Long,
      private val start: Long,
      private val end: Long,
      private val message: String,
  ) : Diagnostic<JavaFileObject> {
    override fun getKind(): Diagnostic.Kind = Diagnostic.Kind.ERROR

    override fun getSource(): JavaFileObject? = null

    override fun getPosition(): Long = start

    override fun getStartPosition(): Long = start

    override fun getEndPosition(): Long = end

    override fun getLineNumber(): Long = line

    override fun getColumnNumber(): Long = column

    override fun getCode(): String = codeValue

    override fun getMessage(locale: Locale?): String = message
  }
}