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

import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import com.tom.rv2ide.lsp.java.utils.TreeUtils;
import com.tom.rv2ide.models.Position;
import com.tom.rv2ide.models.Range;
import openjdk.source.tree.MethodTree;
import openjdk.source.util.TreePath;

/**
 * Conservative guard checks for partial reparse.
 *
 * <p>These guards intentionally cover only low-risk structural assumptions that can be checked
 * before mutating javac AST state. More advanced structure-diff heuristics should be layered on top
 * later, not mixed into the main reparse flow.
 */
public final class PartialReparseGuards {

  private PartialReparseGuards() {}

  @Nullable
  public static String validateCursorWithinMethod(
      @Nullable Pair<Range, TreePath> currentMethod, long cursor) {
    if (currentMethod == null) {
      return "current method not found";
    }

    final Range range = currentMethod.first;
    if (range == null || range.getStart() == null || range.getEnd() == null) {
      return "current method range is incomplete";
    }

    final int startIndex = range.getStart().requireIndex();
    final int endIndex = range.getEnd().requireIndex();
    if (cursor < startIndex || cursor > endIndex) {
      return "cursor is outside current method range";
    }

    return null;
  }

  @Nullable
  public static String validateMethodIsNotConstructor(@Nullable MethodTree methodTree) {
    if (TreeUtils.isConstructor(methodTree)) {
      return "constructors are not eligible for partial reparse";
    }
    return null;
  }

  @Nullable
  public static String validateMethodHasBody(@Nullable MethodTree methodTree) {
    if (methodTree == null) {
      return "current method tree is null";
    }
    if (methodTree.getBody() == null) {
      return "current method has no body";
    }
    return null;
  }

  @Nullable
  public static String validateChangeRangeWithinMethodBody(
      @Nullable Range latestChangeRange, int bodyStart, int bodyEnd) {
    if (latestChangeRange == null) {
      return "latest document change range is unknown";
    }
    final Position changeStart = latestChangeRange.getStart();
    if (changeStart == null) {
      return "latest document change start is unknown";
    }
    final Position changeEnd = latestChangeRange.getEnd();
    if (changeEnd == null) {
      return "latest document change end is unknown";
    }

    final int changeStartIndex = changeStart.requireIndex();
    final int changeEndIndex = changeEnd.requireIndex();
    if (changeStartIndex < bodyStart || changeStartIndex > bodyEnd) {
      return "latest document change starts outside current method body";
    }
    if (changeEndIndex < bodyStart || changeEndIndex > bodyEnd) {
      return "latest document change ends outside current method body";
    }
    return null;
  }

  @Nullable
  public static String validateMethodBodyRange(int start, int end, int contentsLength) {
    if (start < 0) {
      return "method body start is negative";
    }
    if (end < 0) {
      return "method body end is negative";
    }
    if (start > end) {
      return "method body start is after end";
    }
    if (end >= contentsLength) {
      return "method body end is outside document contents";
    }
    return null;
  }
}