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
import androidx.core.util.Pair;
import com.tom.rv2ide.lsp.java.visitors.FindPartialReparseRiskyConstructs;
import com.tom.rv2ide.models.Range;
import openjdk.source.tree.MethodTree;
import openjdk.source.util.TreePath;

/**
 * Preflight checks that must pass before mutating javac AST state with partial reparse.
 *
 * <p>This class keeps the guard ordering testable without constructing a full
 * {@link JavaCompilerService}. It intentionally delegates low-level checks to
 * {@link PartialReparseGuards} and converts guard failures into
 * {@link PartialReparseAttemptResult} values used by the fallback layer.
 */
public final class PartialReparsePreflight {

  private final PartialReparseTextRiskAnalyzer textRiskAnalyzer =
      new PartialReparseTextRiskAnalyzer();

  @Nullable
  public PartialReparseAttemptResult validateCurrentMethod(
      @Nullable Pair<Range, TreePath> currentMethod, long cursor) {
    final String cursorGuardReason =
        PartialReparseGuards.validateCursorWithinMethod(currentMethod, cursor);
    if (cursorGuardReason != null) {
      return PartialReparseAttemptResult.notApplicable(cursorGuardReason);
    }
    return null;
  }

  @Nullable
  public PartialReparseAttemptResult validateMethodTree(@NonNull MethodTree methodTree) {
    final String constructorGuardReason =
        PartialReparseGuards.validateMethodIsNotConstructor(methodTree);
    if (constructorGuardReason != null) {
      return PartialReparseAttemptResult.notApplicable(constructorGuardReason);
    }

    final String methodBodyGuardReason = PartialReparseGuards.validateMethodHasBody(methodTree);
    if (methodBodyGuardReason != null) {
      return PartialReparseAttemptResult.notApplicable(methodBodyGuardReason);
    }

    final FindPartialReparseRiskyConstructs riskyConstructs = new FindPartialReparseRiskyConstructs();
    riskyConstructs.scan(methodTree.getBody(), null);
    if (riskyConstructs.hasRiskyConstructs()) {
      return PartialReparseAttemptResult.notApplicable(riskyConstructs.firstReason());
    }

    return null;
  }

  @Nullable
  public PartialReparseAttemptResult validateRanges(
      @Nullable Range latestChangeRange, int bodyStart, int bodyEnd, int contentsLength) {
    final String rangeGuardReason =
        PartialReparseGuards.validateMethodBodyRange(bodyStart, bodyEnd, contentsLength);
    if (rangeGuardReason != null) {
      return PartialReparseAttemptResult.failed(rangeGuardReason);
    }

    final String changeGuardReason =
        PartialReparseGuards.validateChangeRangeWithinMethodBody(
            latestChangeRange, bodyStart, bodyEnd);
    if (changeGuardReason != null) {
      return PartialReparseAttemptResult.notApplicable(changeGuardReason);
    }

    return null;
  }

  @Nullable
  public PartialReparseAttemptResult validateTextRisk(
      @NonNull final CharSequence contents,
      final long cursor,
      @Nullable final Range latestChangeRange,
      final int bodyStart,
      final int bodyEnd) {
    final String riskReason =
        textRiskAnalyzer.findRiskReason(contents, cursor, latestChangeRange, bodyStart, bodyEnd);
    if (riskReason != null) {
      return PartialReparseAttemptResult.notApplicable(riskReason);
    }
    return null;
  }
}