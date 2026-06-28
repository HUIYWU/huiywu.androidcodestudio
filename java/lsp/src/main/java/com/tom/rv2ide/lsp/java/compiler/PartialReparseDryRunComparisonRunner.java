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

/** Attaches dry-run partial-vs-full snapshot comparison data to a dry-run report. */
public final class PartialReparseDryRunComparisonRunner {

  public static final class PartialSnapshotResult {
    @Nullable public final PartialReparseDryRunSnapshot snapshot;
    @Nullable public final String reason;

    private PartialSnapshotResult(
        @Nullable PartialReparseDryRunSnapshot snapshot, @Nullable String reason) {
      this.snapshot = snapshot;
      this.reason = reason;
    }

    @NonNull
    public static PartialSnapshotResult of(
        @Nullable PartialReparseDryRunSnapshot snapshot, @Nullable String reason) {
      return new PartialSnapshotResult(snapshot, reason);
    }
  }

  @FunctionalInterface
  public interface PartialSnapshotSupplier {
    @NonNull
    PartialSnapshotResult create(@NonNull PartialReparseDryRunReport attemptReport);
  }

  private final PartialReparseDryRunComparator comparator;

  public PartialReparseDryRunComparisonRunner() {
    this(new PartialReparseDryRunComparator());
  }

  PartialReparseDryRunComparisonRunner(@NonNull PartialReparseDryRunComparator comparator) {
    this.comparator = comparator;
  }

  @NonNull
  public PartialReparseDryRunReport attachComparison(
      @NonNull PartialReparseDryRunReport attemptReport,
      @Nullable PartialReparseDryRunSnapshot fullSnapshot,
      @NonNull PartialSnapshotSupplier partialSnapshotSupplier) {
    final PartialSnapshotResult partialSnapshotResult = partialSnapshotSupplier.create(attemptReport);
    final PartialReparseDryRunComparison comparison =
        comparator.compare(partialSnapshotResult.snapshot, fullSnapshot);
    return attemptReport
        .withPartialSnapshotReason(partialSnapshotResult.reason)
        .withComparison(comparison);
  }
}