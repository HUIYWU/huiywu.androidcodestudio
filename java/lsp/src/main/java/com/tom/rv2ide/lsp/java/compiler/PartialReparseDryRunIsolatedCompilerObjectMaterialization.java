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
 * Describes whether a real copied compiler object was materialized from an object-acquisition stage.
 *
 * <p>This keeps "an object could in principle be acquired" separate from "a concrete copied
 * compiler object has actually been materialized and is now available for slot fill".
 */
public final class PartialReparseDryRunIsolatedCompilerObjectMaterialization {

  public enum State {
    NOT_MATERIALIZED,
    RESERVED,
    MATERIALIZED,
    MATERIALIZATION_FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult objectAcquisitionResult;
  @Nullable public final JavaCompilerService compiler;
  public final boolean materializationAttempted;
  public final boolean materializationFailed;

  private PartialReparseDryRunIsolatedCompilerObjectMaterialization(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult objectAcquisitionResult,
      @Nullable JavaCompilerService compiler,
      boolean materializationAttempted,
      boolean materializationFailed) {
    this.state = state;
    this.reason = reason;
    this.objectAcquisitionResult = objectAcquisitionResult;
    this.compiler = compiler;
    this.materializationAttempted = materializationAttempted;
    this.materializationFailed = materializationFailed;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerObjectMaterialization notMaterialized(
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerObjectMaterialization(
        State.NOT_MATERIALIZED,
        reason,
        PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult.notRequested(reason),
        null,
        false,
        false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerObjectMaterialization reserved(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult objectAcquisitionResult) {
    return new PartialReparseDryRunIsolatedCompilerObjectMaterialization(
        State.RESERVED, reason, objectAcquisitionResult, null, false, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerObjectMaterialization materialized(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult objectAcquisitionResult,
      @NonNull JavaCompilerService compiler) {
    return new PartialReparseDryRunIsolatedCompilerObjectMaterialization(
        State.MATERIALIZED, reason, objectAcquisitionResult, compiler, true, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerObjectMaterialization materializationFailed(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult objectAcquisitionResult) {
    return new PartialReparseDryRunIsolatedCompilerObjectMaterialization(
        State.MATERIALIZATION_FAILED, reason, objectAcquisitionResult, null, true, true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedCompilerObjectMaterialization cleanupCompleted(
      @NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedCompilerObjectMaterialization(
        State.CLEANED_UP,
        reason,
        objectAcquisitionResult.cleanupCompleted(reason),
        null,
        true,
        materializationFailed);
  }

  public boolean isReserved() {
    return state == State.RESERVED;
  }

  public boolean isMaterialized() {
    return state == State.MATERIALIZED;
  }

  public boolean isMaterializationFailed() {
    return state == State.MATERIALIZATION_FAILED;
  }

  public boolean isCleanedUp() {
    return state == State.CLEANED_UP;
  }
}