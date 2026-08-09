package com.tom.rv2ide.lsp.java.providers.completion.ts

import com.itsaky.androidide.treesitter.TreeSitter
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
class TSCompletionContextClassifierTest {

  companion object {
    @Volatile private var nativeLoadAttempted = false
    @Volatile private var nativeLoadFailure: Throwable? = null

    @Synchronized
    private fun loadNativeLibraries(): Throwable? {
      if (!nativeLoadAttempted) {
        nativeLoadAttempted = true
        nativeLoadFailure =
            try {
              TreeSitter.loadLibrary()
              System.loadLibrary("tree-sitter-java")
              null
            } catch (error: Throwable) {
              error
            }
      }
      return nativeLoadFailure
    }
  }


  @Test
  fun classifiesCommentContext() {
    val code = "class A { void test() { // hello\n int value = 1; } }"
    val cursor = code.indexOf("hello").toLong()

    val context = classify(code, cursor)

    assertEquals(TSCompletionContext.COMMENT, context)
  }

  @Test
  fun classifiesStringLiteralContext() {
    val code = "class A { void test() { String s = \"abc\"; } }"
    val cursor = code.indexOf("abc").toLong()

    val context = classify(code, cursor)

    assertEquals(TSCompletionContext.STRING_LITERAL, context)
  }

  @Test
  fun classifiesCharacterLiteralContext() {
    val code = "class A { char value = 'x'; }"
    val cursor = code.indexOf("x").toLong()

    val context = classify(code, cursor)

    assertEquals(TSCompletionContext.CHARACTER_LITERAL, context)
  }

  @Test
  fun batchClassificationMatchesSingleCursorClassification() {
    val code = "class A { void test() { String s = \"abc\"; char c = 'x'; } }"
    val stringOffset = code.indexOf("abc")
    val characterOffset = code.indexOf("x")
    val batch = classifyOffsets(code, intArrayOf(stringOffset, characterOffset))

    assertEquals(
        listOf(classify(code, stringOffset.toLong()), classify(code, characterOffset.toLong())),
        batch,
    )
  }

  @Test
  fun classifiesOrdinaryConstructorBodyContext() {
    val code = "class A { A() { int value = 1; } }"
    val context = classify(code, code.indexOf("value").toLong())

    assertEquals(TSCompletionContext.METHOD_BODY, context)
  }

  @Test
  fun classifiesCompactConstructorBodyContext() {
    val code = "record A(int x) { A { int value = x; } }"
    val context = classify(code, code.indexOf("value").toLong())

    assertEquals(TSCompletionContext.METHOD_BODY, context)
  }

  @Test
  fun classifiesImportDeclarationContext() {
    val code = "import java.util.List; class A {}"
    val cursor = code.indexOf("List").toLong()

    val context = classify(code, cursor)

    assertEquals(TSCompletionContext.IMPORT_DECLARATION, context)
  }

  @Test
  fun classifiesPackageDeclarationContext() {
    val code = "package com.example; class A {}"
    val cursor = code.indexOf("example").toLong()

    val context = classify(code, cursor)

    assertEquals(TSCompletionContext.PACKAGE_DECLARATION, context)
  }

  @Test
  fun classifiesMemberAccessContext() {
    val code = "class A { void test() { System.out.println(); } }"
    val cursor = code.indexOf("out").toLong()

    val context = classify(code, cursor)

    assertEquals(TSCompletionContext.MEMBER_ACCESS, context)
  }

  @Test
  fun classifiesMethodCallArgumentsContext() {
    val code = "class A { void test() { call(value); } void call(int value) {} }"
    val cursor = code.indexOf("value").toLong()

    val context = classify(code, cursor)

    assertEquals(TSCompletionContext.METHOD_CALL_ARGUMENTS, context)
  }

  @Test
  fun classifiesMethodBodyContext() {
    val code = "class A { void test() { int value = 1; } }"
    val cursor = code.indexOf("value").toLong()

    val context = classify(code, cursor)

    assertEquals(TSCompletionContext.METHOD_BODY, context)
  }

  @Test
  fun classifiesBrokenSyntaxNearCursor() {
    val code = "class A { void test( { }"
    val cursor = code.lastIndexOf("{").toLong()

    val context = classify(code, cursor)

    assertEquals(TSCompletionContext.BROKEN_SYNTAX_NEAR_CURSOR, context)
  }

  private fun classify(code: String, cursor: Long): TSCompletionContext {
    val offset = cursor.toInt()
    val line = code.substring(0, offset).count { it == '\n' }
    val lastLineBreak = code.lastIndexOf('\n', offset - 1)
    val column = offset - lastLineBreak - 1
    ensureNativeLibrariesLoaded()
    return TSCompletionContextClassifier.classify(
        Paths.get("/tmp/A.java"), code, cursor, line, column)
  }

  private fun classifyOffsets(code: String, offsets: IntArray): List<TSCompletionContext> {
    ensureNativeLibrariesLoaded()
    return TSCompletionContextClassifier.classifyOffsets(Paths.get("/tmp/A.java"), code, offsets)
  }

  private fun ensureNativeLibrariesLoaded() {
    val loadFailure = loadNativeLibraries()
    assertNull(
        "Unable to explicitly load Tree-sitter core/Java native libraries: " +
            nativeFailureDescription(loadFailure),
        loadFailure,
    )
  }

  private fun nativeFailureDescription(error: Throwable?): String {
    if (error == null) return "none"
    return error.javaClass.name + ": " + (error.message ?: error.toString())
  }

}