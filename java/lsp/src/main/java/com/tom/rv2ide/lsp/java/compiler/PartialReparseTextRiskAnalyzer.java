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

package com.tom.rv2ide.lsp.java.compiler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.tom.rv2ide.models.Position;
import com.tom.rv2ide.models.Range;

/**
 * Conservative text-shape risk analyzer for partial reparse.
 *
 * <p>This intentionally uses lightweight local heuristics instead of a full lexer/parser. The goal
 * is not to prove a document is safe, but to cheaply reject edits that are already known to produce
 * unstable partial diagnostics during normal typing.
 */
public final class PartialReparseTextRiskAnalyzer {

  private static final int WINDOW_RADIUS = 80;

  @Nullable
  public String findRiskReason(
      @NonNull final CharSequence contents,
      final long cursor,
      @Nullable final Range latestChangeRange,
      final int bodyStart,
      final int bodyEnd) {
    if (contents.length() == 0) {
      return null;
    }
    final int safeCursor = clampToDocument(cursor, contents.length());
    final int windowStart = Math.max(bodyStart, Math.max(0, safeCursor - WINDOW_RADIUS));
    final int windowEnd = Math.min(contents.length(), Math.min(bodyEnd + 1, safeCursor + WINDOW_RADIUS));
    if (windowStart >= windowEnd) {
      return null;
    }
    final String window = contents.subSequence(windowStart, windowEnd).toString();
    final int relativeCursor = Math.max(0, Math.min(window.length(), safeCursor - windowStart));
    if (hasUnterminatedDoubleQuote(window, relativeCursor)) {
      return "text risk: unterminated string literal near cursor";
    }
    if (isEditingInsideStringAssignment(window, relativeCursor)) {
      return "text risk: editing inside string assignment near cursor";
    }
    if (hasTrailingIdentifierAfterSemicolon(window, relativeCursor)) {
      return "text risk: trailing identifier after semicolon near cursor";
    }
    if (hasIncompleteIdentifierAfterSemicolon(contents, safeCursor, bodyStart, bodyEnd)) {
      return "text risk: incomplete identifier after semicolon near cursor";
    }
    if (looksLikeMissingSemicolonAssignment(contents, latestChangeRange, bodyStart, bodyEnd)) {
      return "text risk: missing semicolon after assignment near latest change";
    }
    if (looksLikeIncompleteStatement(contents, latestChangeRange, bodyStart, bodyEnd)) {
      return "text risk: incomplete statement near latest change";
    }
    return null;
  }


  private int clampToDocument(final long cursor, final int length) {
    if (cursor < 0) {
      return 0;
    }
    if (cursor > length) {
      return length;
    }
    return (int) cursor;
  }

  private boolean hasUnterminatedDoubleQuote(@NonNull final String window, final int relativeCursor) {
    boolean inString = false;
    boolean escaped = false;
    final int scanEnd = Math.max(0, Math.min(window.length(), relativeCursor));
    for (int i = 0; i < scanEnd; i++) {
      final char c = window.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
        continue;
      }
      if (c == '"') {
        inString = !inString;
      }
    }
    return inString;
  }

  private boolean hasTrailingIdentifierAfterSemicolon(
      @NonNull final String window, final int relativeCursor) {
    final int scanEnd = Math.max(0, Math.min(window.length(), relativeCursor));
    final String prefix = window.substring(0, scanEnd);
    int semicolon = prefix.lastIndexOf(';');
    if (semicolon < 0) {
      return false;
    }
    final String trailing = prefix.substring(semicolon + 1).trim();
    if (trailing.isEmpty()) {
      return false;
    }
    if (!trailing.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
      return false;
    }
    return true;
  }

  private boolean isEditingInsideStringAssignment(
      @NonNull final String window, final int relativeCursor) {
    final int scanEnd = Math.max(0, Math.min(window.length(), relativeCursor));
    final String prefix = window.substring(0, scanEnd);
    final int equals = prefix.lastIndexOf('=');
    if (equals < 0) {
      return false;
    }
    final String beforeEquals = prefix.substring(0, equals);
    if (!beforeEquals.matches("(?s).*(String|char\\s*\\[\\s*\\]|Object|var)?\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*$")) {
      return false;
    }
    int quoteCount = 0;
    boolean escaped = false;
    for (int i = equals + 1; i < scanEnd; i++) {
      final char c = prefix.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
        continue;
      }
      if (c == '"') {
        quoteCount++;
      }
    }
    return (quoteCount % 2) == 1;
  }

  private boolean hasIncompleteIdentifierAfterSemicolon(
      @NonNull final CharSequence contents, final int cursor, final int bodyStart, final int bodyEnd) {
    final int lineStart = findLineStart(contents, Math.max(bodyStart, cursor));
    final int lineEnd = findLineEnd(contents, Math.min(contents.length(), Math.max(bodyStart, Math.min(bodyEnd + 1, cursor + 32))));
    if (lineStart >= lineEnd) {
      return false;
    }
    final String linePrefix = contents.subSequence(lineStart, Math.min(cursor, lineEnd)).toString();
    final int semicolon = linePrefix.lastIndexOf(';');
    if (semicolon < 0) {
      return false;
    }
    final String trailing = linePrefix.substring(semicolon + 1).trim();
    if (trailing.isEmpty()) {
      return false;
    }
    if (!trailing.matches("[A-Za-z_$][A-Za-z0-9_$]{0,12}")) {
      return false;
    }
    return trailing.startsWith("Str") || trailing.startsWith("stri") || trailing.startsWith("str");
  }

  private boolean looksLikeMissingSemicolonAssignment(
      @NonNull final CharSequence contents,
      @Nullable final Range latestChangeRange,
      final int bodyStart,
      final int bodyEnd) {
    if (latestChangeRange == null || latestChangeRange.getStart() == null || latestChangeRange.getEnd() == null) {
      return false;
    }
    final Position start = latestChangeRange.getStart();
    final Position end = latestChangeRange.getEnd();
    final int changeStart = start.requireIndex();
    final int changeEnd = end.requireIndex();
    final int lineStart = findLineStart(contents, Math.max(bodyStart, changeStart));
    final int lineEnd = findLineEnd(contents, Math.min(bodyEnd + 1, Math.max(changeStart, changeEnd)));
    if (lineStart >= lineEnd) {
      return false;
    }
    final String changedLine = contents.subSequence(lineStart, lineEnd).toString().trim();
    if (changedLine.isEmpty()) {
      return false;
    }
    if (changedLine.startsWith("//") || changedLine.startsWith("*") || changedLine.startsWith("/*")) {
      return false;
    }
    if (changedLine.endsWith(";") || changedLine.endsWith("{") || changedLine.endsWith("}") || changedLine.endsWith(",")) {
      return false;
    }
    if (!changedLine.contains("=")) {
      return false;
    }
    return changedLine.matches(".*=\\s*(\".*|new\\s+.*|[A-Za-z_$][A-Za-z0-9_$.]*\\s*)");
  }

  private boolean looksLikeIncompleteStatement(
      @NonNull final CharSequence contents,
      @Nullable final Range latestChangeRange,
      final int bodyStart,
      final int bodyEnd) {
    if (latestChangeRange == null || latestChangeRange.getStart() == null || latestChangeRange.getEnd() == null) {
      return false;
    }
    final Position start = latestChangeRange.getStart();
    final Position end = latestChangeRange.getEnd();
    final int changeStart = start.requireIndex();
    final int changeEnd = end.requireIndex();
    final int lineStart = findLineStart(contents, Math.max(bodyStart, changeStart));
    final int lineEnd = findLineEnd(contents, Math.min(bodyEnd + 1, Math.max(changeStart, changeEnd)));
    if (lineStart >= lineEnd) {
      return false;
    }
    final String changedLine = contents.subSequence(lineStart, lineEnd).toString().trim();
    if (changedLine.isEmpty()) {
      return false;
    }
    if (changedLine.startsWith("//") || changedLine.startsWith("*") || changedLine.startsWith("/*")) {
      return false;
    }
    if (changedLine.endsWith(";") || changedLine.endsWith("{") || changedLine.endsWith("}") || changedLine.endsWith(",")) {
      return false;
    }
    return changedLine.contains("=") || changedLine.contains("return") || changedLine.contains("new ");
  }

  private int findLineStart(@NonNull final CharSequence contents, final int from) {
    int i = Math.max(0, Math.min(contents.length(), from));
    while (i > 0) {
      final char c = contents.charAt(i - 1);
      if (c == '\n' || c == '\r') {
        break;
      }
      i--;
    }
    return i;
  }

  private int findLineEnd(@NonNull final CharSequence contents, final int from) {
    int i = Math.max(0, Math.min(contents.length(), from));
    while (i < contents.length()) {
      final char c = contents.charAt(i);
      if (c == '\n' || c == '\r') {
        break;
      }
      i++;
    }
    return i;
  }
}
