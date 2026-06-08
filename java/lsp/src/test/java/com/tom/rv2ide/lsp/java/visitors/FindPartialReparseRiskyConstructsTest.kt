package com.tom.rv2ide.lsp.java.visitors

import java.net.URI
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import openjdk.source.tree.BlockTree
import openjdk.source.tree.ClassTree
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.MethodTree
import openjdk.source.util.JavacTask
import openjdk.tools.javac.api.JavacTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindPartialReparseRiskyConstructsTest {

  @Test
  fun detectsLocalClass() {
    val visitor = scanMethodBody("class A { void test() { class Local {} } }")
    assertTrue(visitor.hasRiskyConstructs())
    assertEquals("current method contains a local class", visitor.firstReason())
  }

  @Test
  fun detectsAnonymousClass() {
    val visitor = scanMethodBody("class A { void test() { Runnable r = new Runnable(){ public void run() {} }; } }")
    assertTrue(visitor.hasRiskyConstructs())
    assertEquals("current method contains an anonymous class", visitor.firstReason())
  }

  @Test
  fun detectsLambda() {
    val visitor = scanMethodBody("class A { void test() { Runnable r = () -> {}; } }")
    assertTrue(visitor.hasRiskyConstructs())
    assertEquals("current method contains a lambda", visitor.firstReason())
  }

  @Test
  fun detectsMethodReference() {
    val visitor = scanMethodBody("class A { void test() { Runnable r = this::run; } void run() {} }")
    assertTrue(visitor.hasRiskyConstructs())
    assertEquals("current method contains a method reference", visitor.firstReason())
  }

  @Test
  fun ignoresPlainMethodBodyWithoutRiskyConstructs() {
    val visitor = scanMethodBody("class A { void test() { int x = 1; x++; } }")
    assertFalse(visitor.hasRiskyConstructs())
    assertEquals(null, visitor.firstReason())
  }

  private fun scanMethodBody(source: String): FindPartialReparseRiskyConstructs {
    val root = parseRoot(source)
    val type = root.typeDecls[0] as ClassTree
    val method = type.members.filterIsInstance<MethodTree>().first { it.name.contentEquals("test") }
    val body = method.body as BlockTree
    val visitor = FindPartialReparseRiskyConstructs()
    visitor.scan(body, null)
    return visitor
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
      SimpleJavaFileObject(URI.create("string:///RiskySample.java"), JavaFileObject.Kind.SOURCE) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = code
    override fun getLastModified(): Long = 1L
  }
}