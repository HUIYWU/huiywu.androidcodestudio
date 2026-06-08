package com.tom.rv2ide.lsp.java.compiler

import androidx.core.util.Pair
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import java.net.URI
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import openjdk.source.tree.ClassTree
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.MethodTree
import openjdk.source.util.JavacTask
import openjdk.tools.javac.api.JavacTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartialReparsePreflightTest {

  @Test
  fun validateCurrentMethod_returnsNotApplicable_whenCurrentMethodIsMissing() {
    val result = PartialReparsePreflight().validateCurrentMethod(null, 10)

    assertEquals(PartialReparseAttemptResult.Status.NOT_APPLICABLE, result?.status)
    assertEquals("current method not found", result?.reason)
  }

  @Test
  fun validateCurrentMethod_returnsNull_whenCursorIsInsideCurrentMethod() {
    val range = Range(Position(0, 0, 5), Position(0, 0, 20))
    val result = PartialReparsePreflight().validateCurrentMethod(Pair(range, null), 10)

    assertNull(result)
  }

  @Test
  fun validateMethodTree_returnsNotApplicable_forConstructor() {
    val result = PartialReparsePreflight().validateMethodTree(parseSingleMethod("class A { A() {} }"))

    assertEquals(PartialReparseAttemptResult.Status.NOT_APPLICABLE, result?.status)
    assertEquals("constructors are not eligible for partial reparse", result?.reason)
  }

  @Test
  fun validateMethodTree_returnsNotApplicable_forMethodWithoutBody() {
    val result =
        PartialReparsePreflight()
            .validateMethodTree(parseSingleMethod("abstract class A { abstract void test(); }"))

    assertEquals(PartialReparseAttemptResult.Status.NOT_APPLICABLE, result?.status)
    assertEquals("current method has no body", result?.reason)
  }

  @Test
  fun validateMethodTree_returnsNotApplicable_forRiskyConstruct() {
    val result =
        PartialReparsePreflight()
            .validateMethodTree(parseSingleMethod("class A { void test() { Runnable r = () -> {}; } }"))

    assertEquals(PartialReparseAttemptResult.Status.NOT_APPLICABLE, result?.status)
    assertEquals("current method contains a lambda", result?.reason)
  }

  @Test
  fun validateMethodTree_returnsNull_forPlainMethodBody() {
    val result = PartialReparsePreflight().validateMethodTree(parseSingleMethod("class A { void test() { int x = 1; } }"))

    assertNull(result)
  }

  @Test
  fun validateRanges_returnsFailed_whenMethodBodyRangeIsInvalid() {
    val result =
        PartialReparsePreflight()
            .validateRanges(Range(Position(0, 0, 5), Position(0, 0, 6)), 10, 20, 20)

    assertEquals(PartialReparseAttemptResult.Status.FAILED, result?.status)
    assertEquals("method body end is outside document contents", result?.reason)
  }

  @Test
  fun validateRanges_returnsNotApplicable_whenChangeRangeIsOutsideMethodBody() {
    val result =
        PartialReparsePreflight()
            .validateRanges(Range(Position(0, 0, 9), Position(0, 0, 12)), 10, 20, 30)

    assertEquals(PartialReparseAttemptResult.Status.NOT_APPLICABLE, result?.status)
    assertEquals("latest document change starts outside current method body", result?.reason)
  }

  @Test
  fun validateRanges_returnsNull_whenRangesAreEligible() {
    val result =
        PartialReparsePreflight()
            .validateRanges(Range(Position(0, 0, 12), Position(0, 0, 15)), 10, 20, 30)

    assertNull(result)
  }

  private fun parseSingleMethod(source: String): MethodTree {
    val root = parseRoot(source)
    val type = root.typeDecls[0] as ClassTree
    return type.members.filterIsInstance<MethodTree>().single()
  }

  private fun parseRoot(source: String): CompilationUnitTree {
    val task =
        JavacTool.create()
            .getTask(
                null,
                null,
                null,
                emptyList<String>(),
                emptyList<String>(),
                listOf(FakeSourceFile(source)),
            ) as JavacTask
    return task.parse().iterator().next()
  }

  private class FakeSourceFile(private val code: String) :
      SimpleJavaFileObject(URI.create("string:///PreflightSample.java"), JavaFileObject.Kind.SOURCE) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = code
    override fun getLastModified(): Long = 1L
  }
}