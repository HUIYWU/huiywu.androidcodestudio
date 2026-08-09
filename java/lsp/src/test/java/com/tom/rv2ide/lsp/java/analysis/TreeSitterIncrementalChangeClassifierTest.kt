/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.lsp.java.analysis

import com.itsaky.androidide.treesitter.TreeSitter
import com.tom.rv2ide.projects.models.DocumentSnapshotIdentity
import com.tom.rv2ide.projects.models.OneHopDocumentEdit
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TreeSitterIncrementalChangeClassifierTest {
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

  private val file: Path = Paths.get("/tmp/Incremental.java")

  @Test
  fun methodBodyEditIsExpressionOrStatement() {
    val content = "class A { void test() { int value = 1; } }"
    val offset = content.indexOf("value")
    assertEquals(
        IncrementalChangeClass.EXPRESSION_OR_STATEMENT,
        classify(content, offset, "X", "value", OneHopDocumentEdit.Kind.REPLACE),
    )
  }

  @Test
  fun ordinaryConstructorBodyEditIsExpressionOrStatement() {
    val content = "class A { A() { int value = 1; } }"
    val offset = content.indexOf("value")
    assertEquals(
        IncrementalChangeClass.EXPRESSION_OR_STATEMENT,
        classify(content, offset, "other", "value", OneHopDocumentEdit.Kind.REPLACE),
    )
  }

  @Test
  fun compactConstructorBodyEditIsExpressionOrStatement() {
    val content = "record A(int x) { A { int value = x; } }"
    val offset = content.indexOf("value")
    assertEquals(
        IncrementalChangeClass.EXPRESSION_OR_STATEMENT,
        classify(content, offset, "other", "value", OneHopDocumentEdit.Kind.REPLACE),
    )
  }

  @Test
  fun importEditIsFileStructure() {
    val content = "import java.util.List; class A {}"
    val offset = content.indexOf("List")
    assertEquals(
        IncrementalChangeClass.FILE_STRUCTURE,
        classify(content, offset, "Set", "List", OneHopDocumentEdit.Kind.REPLACE),
    )
  }

  @Test
  fun typeBodyEditIsMemberDeclaration() {
    val content = "class A { int value; }"
    val offset = content.indexOf("value")
    assertEquals(
        IncrementalChangeClass.MEMBER_DECLARATION,
        classify(content, offset, "other", "value", OneHopDocumentEdit.Kind.REPLACE),
    )
  }

  @Test
  fun commentWhitespaceIsWhitespaceOrComment() {
    val content = "class A { void test() { // hello\n int value = 1; } }"
    val offset = content.indexOf("hello") + "hello".length
    assertEquals(
        IncrementalChangeClass.WHITESPACE_OR_COMMENT,
        classify(content, offset, " ", "", OneHopDocumentEdit.Kind.INSERT),
    )
  }

  @Test
  fun commentWhitespaceWithCrLfOrSurrogatePairIsWhitespaceOrComment() {
    val crLfContent = "class A {\r\n  void test() { // hello\r\n    int value = 1;\r\n  }\r\n}"
    val crLfOffset = crLfContent.indexOf("hello") + "hello".length
    assertEquals(
        IncrementalChangeClass.WHITESPACE_OR_COMMENT,
        classify(crLfContent, crLfOffset, " ", "", OneHopDocumentEdit.Kind.INSERT),
    )

    val surrogateContent = "class A { void test() { String emoji = \"😀\"; // hello\n } }"
    val surrogateOffset = surrogateContent.indexOf("hello") + "hello".length
    assertEquals(
        IncrementalChangeClass.WHITESPACE_OR_COMMENT,
        classify(surrogateContent, surrogateOffset, " ", "", OneHopDocumentEdit.Kind.INSERT),
    )
  }

  @Test
  fun lineTerminatorEditNearLineCommentIsUnknown() {
    val content = "class A { void test() { // note\n int value = 1; } }"
    val offset = content.indexOf("\n")
    assertEquals(
        IncrementalChangeClass.UNKNOWN,
        classify(content, offset, "", "\n", OneHopDocumentEdit.Kind.DELETE),
    )
  }

  @Test
  fun stringOrCharacterWhitespaceIsUnknown() {
    val stringContent = "class A { String value = \"abc\"; }"
    val stringOffset = stringContent.indexOf("abc") + 1
    assertEquals(
        IncrementalChangeClass.UNKNOWN,
        classify(stringContent, stringOffset, " ", "", OneHopDocumentEdit.Kind.INSERT),
    )

    val characterContent = "class A { char value = 'x'; }"
    val characterOffset = characterContent.indexOf("x") + 1
    assertEquals(
        IncrementalChangeClass.UNKNOWN,
        classify(characterContent, characterOffset, " ", "", OneHopDocumentEdit.Kind.INSERT),
    )
  }

  @Test
  fun stringOrBrokenSyntaxIsUnknown() {
    val stringContent = "class A { String value = \"abc\"; }"
    val stringOffset = stringContent.indexOf("abc")
    assertEquals(
        IncrementalChangeClass.UNKNOWN,
        classify(stringContent, stringOffset, "x", "abc", OneHopDocumentEdit.Kind.REPLACE),
    )

    val brokenContent = "class A { void test( { }"
    val brokenOffset = brokenContent.lastIndexOf("{")
    assertEquals(
        IncrementalChangeClass.UNKNOWN,
        classify(brokenContent, brokenOffset, "x", "{", OneHopDocumentEdit.Kind.REPLACE),
    )
  }

  private fun classify(
      content: String,
      offset: Int,
      replacement: String,
      removed: String,
      kind: OneHopDocumentEdit.Kind,
  ): IncrementalChangeClass {
    val loadFailure = loadNativeLibraries()
    assertNull(
        "Unable to explicitly load Tree-sitter core/Java native libraries: " +
            nativeFailureDescription(loadFailure),
        loadFailure,
    )
    val edit =
        OneHopDocumentEdit(
            base = DocumentSnapshotIdentity(file, 1, 1L),
            target = DocumentSnapshotIdentity(file, 2, 2L),
            baseStartIndex = offset,
            baseEndIndex = offset + removed.length,
            removedText = removed,
            replacementText = replacement,
            kind = kind,
        )
    val target = content.replaceRange(offset until offset + removed.length, replacement)
    return TreeSitterIncrementalChangeClassifier.classify(file, target, edit)
  }

  private fun nativeFailureDescription(error: Throwable?): String {
    if (error == null) return "none"
    return error.javaClass.name + ": " + (error.message ?: error.toString())
  }
}