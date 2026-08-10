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

import com.tom.rv2ide.lsp.java.providers.completion.ts.TSCompletionContext
import com.tom.rv2ide.lsp.java.providers.completion.ts.TSCompletionContextClassifier
import com.tom.rv2ide.projects.models.OneHopDocumentEdit
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Maps a verified edit to a conservative semantic-impact class using the existing Tree-sitter
 * completion classifier. This is an input classifier only; javac remains the authority for any
 * candidate/full semantic result.
 */
object TreeSitterIncrementalChangeClassifier {
  private val log = LoggerFactory.getLogger(TreeSitterIncrementalChangeClassifier::class.java)

  fun classify(file: Path, targetContent: String, edit: OneHopDocumentEdit): IncrementalChangeClass {
    if (edit.target.file != file.normalize() || edit.targetIndexOutside(targetContent)) {
      log.warn(
          "Incremental observation classified edit as UNKNOWN reason=TARGET_OR_RANGE_MISMATCH file={} kind={} targetMatchesFile={} start={} baseEnd={} replacementLength={} targetLength={}",
          file,
          edit.kind,
          edit.target.file == file.normalize(),
          edit.baseStartIndex,
          edit.baseEndIndex,
          edit.replacementText.length,
          targetContent.length,
      )
      return IncrementalChangeClass.UNKNOWN
    }

    val startOffset = edit.baseStartIndex
    val endOffset = startOffset + edit.replacementText.length
    val contexts = contextsAt(file, targetContent, startOffset, endOffset)
    val startContext = contexts[0]
    val endContext = contexts[1]
    val changedText = edit.removedText + edit.replacementText
    if (changedText.isNotEmpty() && changedText.all(Char::isWhitespace)) {
      // A line terminator can merge or split a line comment and adjacent code. Even when the
      // target endpoints both resolve to COMMENT, the edit may have changed Java semantics.
      if (changedText.any { it == '\n' || it == '\r' }) {
        logUnknownContext(edit, startOffset, endOffset, startContext, endContext, "WHITESPACE_LINE_BREAK")
        return IncrementalChangeClass.UNKNOWN
      }
      if (startContext == TSCompletionContext.COMMENT && endContext == TSCompletionContext.COMMENT) {
        return IncrementalChangeClass.WHITESPACE_OR_COMMENT
      }

      // A zero-width insertion at the end of a line/block comment is a Tree-sitter point
      // boundary. The non-empty range used by the completion classifier may then resolve to the
      // following parent/neighbor instead of the comment token. Probe the preceding UTF-16 code
      // unit as well, but keep the requirement that both sides are comments.
      val precedingOffset = precedingProbeOffset(startOffset, targetContent)
      val boundaryContexts = contextsAt(
          file,
          targetContent,
          precedingOffset,
          precedingOffset,
      )
      if (boundaryContexts[0] == TSCompletionContext.COMMENT &&
          boundaryContexts[1] == TSCompletionContext.COMMENT) {
        return IncrementalChangeClass.WHITESPACE_OR_COMMENT
      }
      logUnknownContext(edit, startOffset, endOffset, startContext, endContext, "WHITESPACE_NON_COMMENT")
      return IncrementalChangeClass.UNKNOWN
    }

    if (startContext != endContext) {
      logUnknownContext(edit, startOffset, endOffset, startContext, endContext, "CONTEXT_MISMATCH")
      return IncrementalChangeClass.UNKNOWN
    }

    return when (startContext) {
      TSCompletionContext.COMMENT,
      TSCompletionContext.STRING_LITERAL,
      TSCompletionContext.CHARACTER_LITERAL,
      TSCompletionContext.BROKEN_SYNTAX_NEAR_CURSOR,
      TSCompletionContext.UNKNOWN -> {
        logUnknownContext(edit, startOffset, endOffset, startContext, endContext, "UNSAFE_CONTEXT")
        IncrementalChangeClass.UNKNOWN
      }
      TSCompletionContext.IMPORT_DECLARATION,
      TSCompletionContext.PACKAGE_DECLARATION -> IncrementalChangeClass.FILE_STRUCTURE
      TSCompletionContext.TYPE_BODY -> IncrementalChangeClass.MEMBER_DECLARATION
      TSCompletionContext.METHOD_BODY,
      TSCompletionContext.MEMBER_ACCESS,
      TSCompletionContext.METHOD_CALL_ARGUMENTS -> IncrementalChangeClass.EXPRESSION_OR_STATEMENT
    }
  }

  private fun logUnknownContext(
      edit: OneHopDocumentEdit,
      startOffset: Int,
      endOffset: Int,
      startContext: TSCompletionContext,
      endContext: TSCompletionContext,
      reason: String,
  ) {
    log.warn(
        "Incremental observation classified edit as UNKNOWN reason={} kind={} start={} baseEnd={} end={} removedLength={} replacementLength={} removedCodePoints={} replacementCodePoints={} startContext={} endContext={}",
        reason,
        edit.kind,
        startOffset,
        edit.baseEndIndex,
        endOffset,
        edit.removedText.length,
        edit.replacementText.length,
        codePointSummary(edit.removedText),
        codePointSummary(edit.replacementText),
        startContext,
        endContext,
    )
  }

  private fun codePointSummary(text: String): String {
    if (text.isEmpty()) return "[]"
    return text.codePoints().toArray().joinToString(prefix = "[", postfix = "]")
  }

  private fun contextsAt(
      file: Path,
      content: String,
      startOffset: Int,
      endOffset: Int,
  ): List<TSCompletionContext> {
    return try {
      TSCompletionContextClassifier.classifyOffsets(
          file, content, intArrayOf(startOffset, endOffset))
    } catch (_: Throwable) {
      // Native Tree-sitter handles can fail transiently. Classification failure must be safe.
      listOf(TSCompletionContext.UNKNOWN, TSCompletionContext.UNKNOWN)
    }
  }

  private fun precedingProbeOffset(offset: Int, content: String): Int {
    return if (offset > 0 && offset <= content.length) offset - 1 else offset
  }

  private fun OneHopDocumentEdit.targetIndexOutside(targetContent: String): Boolean {
    val targetEnd = baseStartIndex + replacementText.length
    return baseStartIndex < 0 || baseEndIndex < baseStartIndex || targetEnd < baseStartIndex ||
        targetEnd > targetContent.length
  }
}