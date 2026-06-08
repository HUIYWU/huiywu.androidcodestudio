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
import java.util.List;

/** Pure comparator for dry-run partial snapshots and full-recompile snapshots. */
public final class PartialReparseDryRunComparator {

  @NonNull
  public PartialReparseDryRunComparison compare(
      @Nullable PartialReparseDryRunSnapshot partialSnapshot,
      @Nullable PartialReparseDryRunSnapshot fullSnapshot) {
    if (partialSnapshot == null) {
      return PartialReparseDryRunComparison.incomplete("partial dry-run snapshot is missing");
    }
    if (fullSnapshot == null) {
      return PartialReparseDryRunComparison.incomplete("full recompile snapshot is missing");
    }

    return PartialReparseDryRunComparison.fromStates(
        compareLists(partialSnapshot.diagnostics, fullSnapshot.diagnostics),
        compareLists(partialSnapshot.methodPositionKeys, fullSnapshot.methodPositionKeys),
        compareLists(partialSnapshot.sourcePositionKeys, fullSnapshot.sourcePositionKeys),
        null);
  }

  @NonNull
  private static PartialReparseDryRunComparison.ComparisonState compareLists(
      @NonNull List<String> partialValues, @NonNull List<String> fullValues) {
    return partialValues.equals(fullValues)
        ? PartialReparseDryRunComparison.ComparisonState.MATCH
        : PartialReparseDryRunComparison.ComparisonState.MISMATCH;
  }
}