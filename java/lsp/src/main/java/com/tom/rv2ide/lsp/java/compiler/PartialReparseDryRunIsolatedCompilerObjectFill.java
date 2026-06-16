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
 * Describes the step that would fill a reserved compiler slot with a real copied compiler object.
 *
 * <p>This seam remains non-executing by default, but it is the first one that can explicitly model
 * slot fill success/failure without conflating that with reference binding or compile execution.
 */
public final class PartialReparseDryRunIsolatedCompilerObjectFill {

  public enum State {
    NOT_FILLED,
    RESERVED,
    FILLED,
    FILL_FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedCompilerSlot compilerSlot;
  @Nullable public final JavaCompilerService compiler;
  public final boolean fillAttempted;
  public final boolean fillFailed;

  private PartialReparseDryRunIsolatedCompilerObjectFill(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerSlot compilerSlot,
      @Nullable JavaCompilerService compiler,
      boolean fillAttempted,
      boolean fillFailed) {
    this.state = state;
    this.reason = reason;
    this.compilerSlot = compilerSlot;
    this.compiler = compiler;
    this.fillAttempted = fillAttempted;
    this.fillFailed = fillFailed;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerObjectFill notFilled(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerObjectFill(
        State.NOT_FILLED,
        reason,
        PartialReparseDryRunIsolatedCompilerSlot.notAllocated(reason),
        null,
        false,
        false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerObjectFill reserved(
      @NonNull String reason, @NonNull PartialReparseDryRunIsolatedCompilerSlot compilerSlot) {
    return new PartialReparseDryRunIsolatedCompilerObjectFill(
        State.RESERVED, reason, compilerSlot, null, false, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerObjectFill filled(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerSlot compilerSlot,
      @NonNull JavaCompilerService compiler) {
    return new PartialReparseDryRunIsolatedCompilerObjectFill(
        State.FILLED, reason, compilerSlot, compiler, true, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerObjectFill fillFailed(
      @NonNull String reason, @NonNull PartialReparseDryRunIsolatedCompilerSlot compilerSlot) {
    return new PartialReparseDryRunIsolatedCompilerObjectFill(
        State.FILL_FAILED, reason, compilerSlot, null, true, true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedCompilerObjectFill cleanupCompleted(@NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedCompilerObjectFill(
        State.CLEANED_UP, reason, compilerSlot.release(reason), null, true, fillFailed);
  }

  public boolean isReserved() {
    return state == State.RESERVED;
  }

  public boolean isFilled() {
    return state == State.FILLED;
  }

  public boolean isFillFailed() {
    return state == State.FILL_FAILED;
  }

  public boolean isCleanedUp() {
    return state == State.CLEANED_UP;
  }
}