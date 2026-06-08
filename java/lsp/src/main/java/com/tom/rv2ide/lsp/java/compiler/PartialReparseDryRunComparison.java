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

/** Comparison result between an isolated dry-run partial snapshot and full-recompile snapshot. */
public final class PartialReparseDryRunComparison {

  public enum ComparisonState {
    NOT_RUN,
    MATCH,
    MISMATCH,
    INCOMPLETE
  }

  @NonNull public final ComparisonState diagnosticsComparison;
  @NonNull public final ComparisonState methodPositionsComparison;
  @NonNull public final ComparisonState sourcePositionsComparison;
  @Nullable public final String reason;

  private PartialReparseDryRunComparison(
      @NonNull ComparisonState diagnosticsComparison,
      @NonNull ComparisonState methodPositionsComparison,
      @NonNull ComparisonState sourcePositionsComparison,
      @Nullable String reason) {
    this.diagnosticsComparison = diagnosticsComparison;
    this.methodPositionsComparison = methodPositionsComparison;
    this.sourcePositionsComparison = sourcePositionsComparison;
    this.reason = reason;
  }

  @NonNull
  public static PartialReparseDryRunComparison notRun() {
    return new PartialReparseDryRunComparison(
        ComparisonState.NOT_RUN, ComparisonState.NOT_RUN, ComparisonState.NOT_RUN, null);
  }

  @NonNull
  public static PartialReparseDryRunComparison incomplete(@NonNull String reason) {
    return new PartialReparseDryRunComparison(
        ComparisonState.INCOMPLETE, ComparisonState.INCOMPLETE, ComparisonState.INCOMPLETE, reason);
  }

  @NonNull
  public static PartialReparseDryRunComparison fromStates(
      @NonNull ComparisonState diagnosticsComparison,
      @NonNull ComparisonState methodPositionsComparison,
      @NonNull ComparisonState sourcePositionsComparison,
      @Nullable String reason) {
    return new PartialReparseDryRunComparison(
        diagnosticsComparison, methodPositionsComparison, sourcePositionsComparison, reason);
  }
}