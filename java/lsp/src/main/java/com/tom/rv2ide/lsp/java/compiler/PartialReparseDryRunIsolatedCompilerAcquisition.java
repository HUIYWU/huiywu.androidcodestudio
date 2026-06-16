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
 * Describes the acquisition step that would move a compiler slot from reserved to filled.
 *
 * <p>This seam still allows the default implementation to stay non-executing while making object
 * acquisition, acquisition failure and failure-triggered cleanup explicit.
 */
public final class PartialReparseDryRunIsolatedCompilerAcquisition {

  public enum State {
    NOT_ACQUIRED,
    RESERVED,
    ACQUIRED,
    FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedCompilerSlot compilerSlot;
  @NonNull public final PartialReparseDryRunIsolatedCleanupExecutor cleanupExecutor;
  public final boolean acquisitionAttempted;
  public final boolean acquisitionFailed;

  private PartialReparseDryRunIsolatedCompilerAcquisition(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerSlot compilerSlot,
      @NonNull PartialReparseDryRunIsolatedCleanupExecutor cleanupExecutor,
      boolean acquisitionAttempted,
      boolean acquisitionFailed) {
    this.state = state;
    this.reason = reason;
    this.compilerSlot = compilerSlot;
    this.cleanupExecutor = cleanupExecutor;
    this.acquisitionAttempted = acquisitionAttempted;
    this.acquisitionFailed = acquisitionFailed;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerAcquisition notAcquired(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerAcquisition(
        State.NOT_ACQUIRED,
        reason,
        PartialReparseDryRunIsolatedCompilerSlot.notAllocated(reason),
        PartialReparseDryRunIsolatedCleanupExecutor.notNeeded(reason),
        false,
        false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerAcquisition reserved(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerSlot compilerSlot,
      @NonNull PartialReparseDryRunIsolatedCleanupExecutor cleanupExecutor) {
    return new PartialReparseDryRunIsolatedCompilerAcquisition(
        State.RESERVED, reason, compilerSlot, cleanupExecutor, false, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerAcquisition acquired(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerSlot compilerSlot,
      @NonNull PartialReparseDryRunIsolatedCleanupExecutor cleanupExecutor) {
    return new PartialReparseDryRunIsolatedCompilerAcquisition(
        State.ACQUIRED, reason, compilerSlot, cleanupExecutor, true, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerAcquisition failed(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerSlot compilerSlot,
      @NonNull PartialReparseDryRunIsolatedCleanupExecutor cleanupExecutor) {
    return new PartialReparseDryRunIsolatedCompilerAcquisition(
        State.FAILED, reason, compilerSlot, cleanupExecutor, true, true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedCompilerAcquisition cleanupCompleted(@NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedCompilerAcquisition(
        State.CLEANED_UP,
        reason,
        compilerSlot.release(reason),
        cleanupExecutor.execute(reason),
        true,
        acquisitionFailed);
  }

  public boolean isReserved() {
    return state == State.RESERVED;
  }

  public boolean isAcquired() {
    return state == State.ACQUIRED;
  }

  public boolean isFailed() {
    return state == State.FAILED;
  }

  public boolean isCleanedUp() {
    return state == State.CLEANED_UP;
  }
}