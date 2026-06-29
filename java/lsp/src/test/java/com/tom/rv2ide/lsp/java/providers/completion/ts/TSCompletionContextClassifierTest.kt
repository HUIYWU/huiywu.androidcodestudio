package com.tom.rv2ide.lsp.java.providers.completion.ts

import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Test

class TSCompletionContextClassifierTest {

  @Test
  fun classifiesCommentContext() {
    val code = "class A { void test() { // hello\n int value = 1; } }"
    val cursor = code.indexOf("hello").toLong()

    val context = TSCompletionContextClassifier.classify(Paths.get("/tmp/A.java"), code, cursor)

    assertEquals(TSCompletionContext.COMMENT_OR_STRING, context)
  }

  @Test
  fun classifiesStringLiteralContext() {
    val code = "class A { void test() { String s = \"abc\"; } }"
    val cursor = code.indexOf("abc").toLong()

    val context = TSCompletionContextClassifier.classify(Paths.get("/tmp/A.java"), code, cursor)

    assertEquals(TSCompletionContext.COMMENT_OR_STRING, context)
  }

  @Test
  fun classifiesImportDeclarationContext() {
    val code = "import java.util.List; class A {}"
    val cursor = code.indexOf("List").toLong()

    val context = TSCompletionContextClassifier.classify(Paths.get("/tmp/A.java"), code, cursor)

    assertEquals(TSCompletionContext.IMPORT_DECLARATION, context)
  }

  @Test
  fun classifiesPackageDeclarationContext() {
    val code = "package com.example; class A {}"
    val cursor = code.indexOf("example").toLong()

    val context = TSCompletionContextClassifier.classify(Paths.get("/tmp/A.java"), code, cursor)

    assertEquals(TSCompletionContext.PACKAGE_DECLARATION, context)
  }

  @Test
  fun classifiesMemberAccessContext() {
    val code = "class A { void test() { System.out.println(); } }"
    val cursor = code.indexOf("out").toLong()

    val context = TSCompletionContextClassifier.classify(Paths.get("/tmp/A.java"), code, cursor)

    assertEquals(TSCompletionContext.MEMBER_ACCESS, context)
  }

  @Test
  fun classifiesMethodCallArgumentsContext() {
    val code = "class A { void test() { call(value); } void call(int value) {} }"
    val cursor = code.indexOf("value").toLong()

    val context = TSCompletionContextClassifier.classify(Paths.get("/tmp/A.java"), code, cursor)

    assertEquals(TSCompletionContext.METHOD_CALL_ARGUMENTS, context)
  }

  @Test
  fun classifiesMethodBodyContext() {
    val code = "class A { void test() { int value = 1; } }"
    val cursor = code.indexOf("value").toLong()

    val context = TSCompletionContextClassifier.classify(Paths.get("/tmp/A.java"), code, cursor)

    assertEquals(TSCompletionContext.METHOD_BODY, context)
  }

  @Test
  fun classifiesBrokenSyntaxNearCursor() {
    val code = "class A { void test( { }"
    val cursor = code.indexOf("{").toLong()

    val context = TSCompletionContextClassifier.classify(Paths.get("/tmp/A.java"), code, cursor)

    assertEquals(TSCompletionContext.BROKEN_SYNTAX_NEAR_CURSOR, context)
  }
}