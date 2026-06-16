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

/**
 * Describes how cleanup would be executed for a copied compiler lifecycle object.
 *
 * <p>This seam is still non-executing. It exists to model the path and owner that would perform
 * cleanup once real compiler objects start participating in isolated dry-run flows.
 */
public final class PartialReparseDryRunIsolatedCleanupExecutor {

  public enum State {
    NOT_NEEDED,
    PENDING,
    EXECUTED
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedCleanupPlan cleanupPlan;
  @NonNull public final PartialReparseDryRunIsolatedCompilerSlot compilerSlot;
  public final boolean shouldRunOnFailure;
  public final boolean shouldRunOnSuccess;

  private PartialReparseDryRunIsolatedCleanupExecutor(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCleanupPlan cleanupPlan,
      @NonNull PartialReparseDryRunIsolatedCompilerSlot compilerSlot,
      boolean shouldRunOnFailure,
      boolean shouldRunOnSuccess) {
    this.state = state;
    this.reason = reason;
    this.cleanupPlan = cleanupPlan;
    this.compilerSlot = compilerSlot;
    this.shouldRunOnFailure = shouldRunOnFailure;
    this.shouldRunOnSuccess = shouldRunOnSuccess;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCleanupExecutor notNeeded(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedCleanupExecutor(
        State.NOT_NEEDED,
        reason,
        PartialReparseDryRunIsolatedCleanupPlan.notRequired(reason),
        PartialReparseDryRunIsolatedCompilerSlot.notAllocated(reason),
        false,
        false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCleanupExecutor pending(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCleanupPlan cleanupPlan,
      @NonNull PartialReparseDryRunIsolatedCompilerSlot compilerSlot,
      boolean shouldRunOnFailure,
      boolean shouldRunOnSuccess) {
    return new PartialReparseDryRunIsolatedCleanupExecutor(
        State.PENDING,
        reason,
        cleanupPlan,
        compilerSlot,
        shouldRunOnFailure,
        shouldRunOnSuccess);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCleanupExecutor executed(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerSlot compilerSlot) {
    return new PartialReparseDryRunIsolatedCleanupExecutor(
        State.EXECUTED,
        reason,
        PartialReparseDryRunIsolatedCleanupPlan.completed(reason),
        compilerSlot.release(reason),
        false,
        false);
  }

  @NonNull
  public PartialReparseDryRunIsolatedCleanupExecutor execute(@NonNull String reason) {
    if (isExecuted()) {
      return this;
    }
    return executed(reason, compilerSlot);
  }

  public boolean isPending() {
    return state == State.PENDING;
  }

  public boolean isExecuted() {
    return state == State.EXECUTED;
  }
}