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
 * Describes whether a compiler reference is ready to be consumed by higher lifecycle stages.
 *
 * <p>This keeps "binding produced a reference" separate from "that reference is now considered
 * stable enough for handle/candidate/session consumption".
 */
public final class PartialReparseDryRunIsolatedCompilerReferenceReadyResult {

  public enum State {
    NOT_READY,
    DEFERRED,
    READY,
    READY_FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedCompilerBindingResult bindingResult;
  @NonNull public final PartialReparseDryRunIsolatedCompilerReference reference;
  public final boolean readyCheckAttempted;
  public final boolean readyFailed;

  private PartialReparseDryRunIsolatedCompilerReferenceReadyResult(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerBindingResult bindingResult,
      @NonNull PartialReparseDryRunIsolatedCompilerReference reference,
      boolean readyCheckAttempted,
      boolean readyFailed) {
    this.state = state;
    this.reason = reason;
    this.bindingResult = bindingResult;
    this.reference = reference;
    this.readyCheckAttempted = readyCheckAttempted;
    this.readyFailed = readyFailed;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerReferenceReadyResult notReady(
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerReferenceReadyResult(
        State.NOT_READY,
        reason,
        PartialReparseDryRunIsolatedCompilerBindingResult.notBound(reason),
        PartialReparseDryRunIsolatedCompilerReference.notAvailable(reason),
        false,
        false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerReferenceReadyResult deferred(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerBindingResult bindingResult,
      @NonNull PartialReparseDryRunIsolatedCompilerReference reference) {
    return new PartialReparseDryRunIsolatedCompilerReferenceReadyResult(
        State.DEFERRED, reason, bindingResult, reference, false, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerReferenceReadyResult ready(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerBindingResult bindingResult,
      @NonNull PartialReparseDryRunIsolatedCompilerReference reference) {
    return new PartialReparseDryRunIsolatedCompilerReferenceReadyResult(
        State.READY, reason, bindingResult, reference, true, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerReferenceReadyResult readyFailed(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerBindingResult bindingResult,
      @NonNull PartialReparseDryRunIsolatedCompilerReference reference) {
    return new PartialReparseDryRunIsolatedCompilerReferenceReadyResult(
        State.READY_FAILED, reason, bindingResult, reference, true, true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedCompilerReferenceReadyResult cleanupCompleted(
      @NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedCompilerReferenceReadyResult(
        State.CLEANED_UP,
        reason,
        bindingResult.cleanupCompleted(reason),
        reference.release(reason),
        true,
        readyFailed);
  }

  public boolean isDeferred() {
    return state == State.DEFERRED;
  }

  public boolean isReady() {
    return state == State.READY;
  }

  public boolean isReadyFailed() {
    return state == State.READY_FAILED;
  }

  public boolean isCleanedUp() {
    return state == State.CLEANED_UP;
  }
}