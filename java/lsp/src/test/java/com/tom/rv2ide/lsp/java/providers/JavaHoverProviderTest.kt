package com.tom.rv2ide.lsp.java.providers

import java.net.URI
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.element.VariableElement
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import openjdk.source.util.JavacTask
import openjdk.tools.javac.api.JavacTool
import org.junit.Assert.assertEquals
import org.junit.Test

class JavaHoverProviderTest {

  @Test
  fun formatsCompilerAttributedGenericAndWildcardSurfaces() {
    val owner = compileType(
      """
      package hover;
      class MultipleBounds<T extends CharSequence & Comparable<? super T>> {
        public java.util.List<? extends String> forced;
        MultipleBounds(T value) {}
        <R extends CharSequence & Comparable<? super R>> R multiple(R value) { return value; }
      }
      """.trimIndent(),
      "hover.MultipleBounds",
    )
    val method = owner.enclosedElements
      .filterIsInstance<ExecutableElement>()
      .single { it.simpleName.contentEquals("multiple") }
    val constructor = owner.enclosedElements
      .filterIsInstance<ExecutableElement>()
      .single { it.simpleName.contentEquals("<init>") }
    val field = owner.enclosedElements
      .filterIsInstance<VariableElement>()
      .single { it.simpleName.contentEquals("forced") }

    assertEquals(
      "class MultipleBounds<T extends CharSequence & Comparable<? super T>>",
      JavaHoverProvider.formatSignature(owner),
    )
    assertEquals(
      "<R extends CharSequence & Comparable<? super R>> R multiple(R value)",
      JavaHoverProvider.formatSignature(method),
    )
    assertEquals("MultipleBounds(T value)", JavaHoverProvider.formatSignature(constructor))
    assertEquals("List<? extends String> forced", JavaHoverProvider.formatSignature(field))
    assertEquals("T extends CharSequence & Comparable<? super T>",
      JavaHoverProvider.formatSignature(owner.typeParameters.single()))
  }

  private fun compileType(source: String, qualifiedName: String): TypeElement {
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
    task.parse()
    task.analyze()
    return task.elements.getTypeElement(qualifiedName) as TypeElement
  }

  private class FakeSourceFile(private val source: String) :
    SimpleJavaFileObject(URI.create("string:///HoverFixture.java"), JavaFileObject.Kind.SOURCE) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
  }
}
