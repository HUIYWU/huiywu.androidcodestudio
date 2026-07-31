package com.tom.rv2ide.lsp.java.kotlin

import com.itsaky.androidide.treesitter.TreeSitter
import java.net.URI
import java.nio.file.Paths
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.TypeElement
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import openjdk.source.util.JavacTask
import openjdk.tools.javac.api.JavacTool
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test

/** Direct regression coverage for conservative Java ABI element to Kotlin source navigation. */
class KotlinJvmSourceNavigatorTest {

  @Test
  fun facadeNavigation_selectsGenericAliasOverloadByAttributedParameterType() {
    val kotlinSource =
      """
      package navigation

      typealias NavigationListAlias<T> = List<T>

      fun navigateAlias(value: NavigationListAlias<String>): Unit {}
      fun navigateAlias(value: NavigationListAlias<Int>): Unit {}
      """.trimIndent()
    val method = compileMethod(
      """
      package navigation;
      abstract class NavigationApiKt {
        abstract void navigateAlias(java.util.List<java.lang.Integer> value);
      }
      """.trimIndent(),
      "navigation.NavigationApiKt",
      "navigateAlias",
    )

    val location = KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/NavigationApi.kt"), kotlinSource, method)

    assertNotNull(location)
  }

  @Test
  fun facadeNavigation_refusesAmbiguousUnsupportedKotlinTypes() {
    val kotlinSource =
      """
      package navigation

      fun ambiguous(value: (Int) -> Unit): Unit {}
      fun ambiguous(value: (String) -> Unit): Unit {}
      """.trimIndent()
    val method = compileMethod(
      """
      package navigation;
      abstract class AmbiguousApiKt {
        abstract void ambiguous(java.lang.Object value);
      }
      """.trimIndent(),
      "navigation.AmbiguousApiKt",
      "ambiguous",
    )

    val location = KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/AmbiguousApi.kt"), kotlinSource, method)

    assertNull(location)
  }

  private fun compileMethod(
    source: String,
    qualifiedOwner: String,
    methodName: String,
  ): ExecutableElement {
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
    val owner = task.elements.getTypeElement(qualifiedOwner) as TypeElement
    return owner.enclosedElements
      .filterIsInstance<ExecutableElement>()
      .single { it.simpleName.contentEquals(methodName) }
  }

  private class FakeSourceFile(private val code: String) :
    SimpleJavaFileObject(URI.create("string:///NavigationApiKt.java"), JavaFileObject.Kind.SOURCE) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = code
    override fun getLastModified(): Long = 1L
  }

  companion object {
    @JvmStatic
    @BeforeClass
    fun loadParserLibraries() {
      TreeSitter.loadLibrary()
      System.loadLibrary("tree-sitter-kotlin")
    }
  }
}
