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
 * Describes the result of attempting to obtain a real copied compiler object for an isolated slot.
 *
 * <p>This keeps "slot/acquisition lifecycle exists" separate from "a concrete compiler object was
 * actually obtained", which is the last major seam before real object-fill / bind-success logic.
 */
public final class PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult {

  public enum State {
    NOT_REQUESTED,
    RESERVED,
    ACQUIRED,
    ACQUISITION_FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedCompilerAcquisition acquisition;
  @Nullable public final JavaCompilerService compiler;
  public final boolean acquisitionAttempted;
  public final boolean acquisitionFailed;

  private PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerAcquisition acquisition,
      @Nullable JavaCompilerService compiler,
      boolean acquisitionAttempted,
      boolean acquisitionFailed) {
    this.state = state;
    this.reason = reason;
    this.acquisition = acquisition;
    this.compiler = compiler;
    this.acquisitionAttempted = acquisitionAttempted;
    this.acquisitionFailed = acquisitionFailed;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult notRequested(
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult(
        State.NOT_REQUESTED,
        reason,
        PartialReparseDryRunIsolatedCompilerAcquisition.notAcquired(reason),
        null,
        false,
        false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult reserved(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerAcquisition acquisition) {
    return new PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult(
        State.RESERVED, reason, acquisition, null, false, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult acquired(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerAcquisition acquisition,
      @NonNull JavaCompilerService compiler) {
    return new PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult(
        State.ACQUIRED, reason, acquisition, compiler, true, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult acquisitionFailed(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerAcquisition acquisition) {
    return new PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult(
        State.ACQUISITION_FAILED, reason, acquisition, null, true, true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult cleanupCompleted(
      @NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult(
        State.CLEANED_UP,
        reason,
        acquisition.cleanupCompleted(reason),
        null,
        true,
        acquisitionFailed);
  }

  public boolean isReserved() {
    return state == State.RESERVED;
  }

  public boolean isAcquired() {
    return state == State.ACQUIRED;
  }

  public boolean isAcquisitionFailed() {
    return state == State.ACQUISITION_FAILED;
  }

  public boolean isCleanedUp() {
    return state == State.CLEANED_UP;
  }
}
