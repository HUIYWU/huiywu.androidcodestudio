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

/**
 * Observable result of a dry-run partial reparse branch.
 *
 * <p>Dry-run reports are intentionally explicit that the partial result is never committed and the
 * stable full-recompile path remains the user-visible result. Future isolated dry-run work can extend
 * this object with diagnostics/method-position comparison data without changing the branch invariant.
 */
public final class PartialReparseDryRunReport {

  public enum AttemptState {
    NOT_CREATED,
    SUCCESS,
    NOT_APPLICABLE,
    FAILED,
    EXCEPTION
  }

  @NonNull public final AttemptState attemptState;
  @Nullable public final String reason;
  @Nullable public final String partialSnapshotReason;
  public final boolean fullRecompileExecuted;
  public final boolean partialResultCommitted;
  @NonNull public final PartialReparseDryRunComparison comparison;

  private PartialReparseDryRunReport(
      @NonNull AttemptState attemptState,
      @Nullable String reason,
      @Nullable String partialSnapshotReason,
      boolean fullRecompileExecuted,
      boolean partialResultCommitted,
      @NonNull PartialReparseDryRunComparison comparison) {
    this.attemptState = attemptState;
    this.reason = reason;
    this.partialSnapshotReason = partialSnapshotReason;
    this.fullRecompileExecuted = fullRecompileExecuted;
    this.partialResultCommitted = partialResultCommitted;
    this.comparison = comparison;
  }

  @NonNull
  public static PartialReparseDryRunReport notCreated() {
    return new PartialReparseDryRunReport(
        AttemptState.NOT_CREATED, null, null, true, false, PartialReparseDryRunComparison.notRun());
  }

  @NonNull
  public static PartialReparseDryRunReport fromAttemptResult(
      @NonNull PartialReparseAttemptResult result) {
    final AttemptState state;
    if (result.isSuccess()) {
      state = AttemptState.SUCCESS;
    } else if (result.status == PartialReparseAttemptResult.Status.NOT_APPLICABLE) {
      state = AttemptState.NOT_APPLICABLE;
    } else {
      state = AttemptState.FAILED;
    }
    return new PartialReparseDryRunReport(
        state, result.reason, null, true, false, PartialReparseDryRunComparison.notRun());
  }

  @NonNull
  public static PartialReparseDryRunReport exception(@NonNull Throwable error) {
    return new PartialReparseDryRunReport(
        AttemptState.EXCEPTION, error.getMessage(), null, true, false, PartialReparseDryRunComparison.notRun());
  }

  @NonNull
  public PartialReparseDryRunReport withComparison(
      @NonNull PartialReparseDryRunComparison comparison) {
    return new PartialReparseDryRunReport(
        attemptState, reason, partialSnapshotReason, fullRecompileExecuted, partialResultCommitted, comparison);
  }

  @NonNull
  public PartialReparseDryRunReport withPartialSnapshotReason(@Nullable String partialSnapshotReason) {
    return new PartialReparseDryRunReport(
        attemptState, reason, partialSnapshotReason, fullRecompileExecuted, partialResultCommitted, comparison);
  }
}