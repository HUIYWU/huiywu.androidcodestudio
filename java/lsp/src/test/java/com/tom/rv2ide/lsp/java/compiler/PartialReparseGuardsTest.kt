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

class PartialReparseGuardsTest {

  @Test
  fun validateCursorWithinMethod_returnsNull_whenCursorInsideMethodRange() {
    val methodRange = Range(Position(0, 0, 5), Position(0, 0, 20))
    val result = PartialReparseGuards.validateCursorWithinMethod(Pair(methodRange, null), 10)
    assertNull(result)
  }

  @Test
  fun validateCursorWithinMethod_returnsReason_whenCursorOutsideMethodRange() {
    val methodRange = Range(Position(0, 0, 5), Position(0, 0, 20))
    val result = PartialReparseGuards.validateCursorWithinMethod(Pair(methodRange, null), 25)
    assertEquals("cursor is outside current method range", result)
  }

  @Test
  fun validateChangeRangeWithinMethodBody_returnsNull_whenWholeChangeInsideBody() {
    val range = Range(Position(0, 0, 12), Position(0, 0, 15))
    val result = PartialReparseGuards.validateChangeRangeWithinMethodBody(range, 10, 20)
    assertNull(result)
  }

  @Test
  fun validateChangeRangeWithinMethodBody_rejectsStartOutsideBody() {
    val range = Range(Position(0, 0, 9), Position(0, 0, 15))
    val result = PartialReparseGuards.validateChangeRangeWithinMethodBody(range, 10, 20)
    assertEquals("latest document change starts outside current method body", result)
  }

  @Test
  fun validateChangeRangeWithinMethodBody_rejectsEndOutsideBody() {
    val range = Range(Position(0, 0, 12), Position(0, 0, 21))
    val result = PartialReparseGuards.validateChangeRangeWithinMethodBody(range, 10, 20)
    assertEquals("latest document change ends outside current method body", result)
  }

  @Test
  fun validateMethodBodyRange_returnsNull_whenRangeIsValid() {
    assertNull(PartialReparseGuards.validateMethodBodyRange(5, 10, 20))
  }

  @Test
  fun validateMethodBodyRange_rejectsEndOutsideContents() {
    assertEquals(
        "method body end is outside document contents",
        PartialReparseGuards.validateMethodBodyRange(5, 20, 20),
    )
  }

  @Test
  fun validateMethodIsNotConstructor_rejectsConstructor() {
    val method = parseSingleMethod("class A { A() {} }")
    assertEquals(
        "constructors are not eligible for partial reparse",
        PartialReparseGuards.validateMethodIsNotConstructor(method),
    )
  }

  @Test
  fun validateMethodIsNotConstructor_acceptsRegularMethod() {
    val method = parseSingleMethod("class A { void test() {} }")
    assertNull(PartialReparseGuards.validateMethodIsNotConstructor(method))
  }

  @Test
  fun validateMethodHasBody_rejectsAbstractMethod() {
    val method = parseSingleMethod("abstract class A { abstract void test(); }")
    assertEquals("current method has no body", PartialReparseGuards.validateMethodHasBody(method))
  }

  @Test
  fun validateMethodHasBody_acceptsMethodWithBody() {
    val method = parseSingleMethod("class A { void test() {} }")
    assertNull(PartialReparseGuards.validateMethodHasBody(method))
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
      SimpleJavaFileObject(URI.create("string:///GuardSample.java"), JavaFileObject.Kind.SOURCE) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = code
    override fun getLastModified(): Long = 1L
  }
}