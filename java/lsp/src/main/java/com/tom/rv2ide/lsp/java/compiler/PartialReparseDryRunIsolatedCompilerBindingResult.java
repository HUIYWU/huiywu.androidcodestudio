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
 * Describes how an acquisition result is bound into a compiler reference lifecycle stage.
 *
 * <p>This seam still allows the default implementation to remain non-executing while making the
 * success/failure of real object binding explicit.
 */
public final class PartialReparseDryRunIsolatedCompilerBindingResult {

  public enum State {
    NOT_BOUND,
    DEFERRED,
    BOUND,
    BINDING_FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedCompilerAcquisition acquisition;
  @NonNull public final PartialReparseDryRunIsolatedCompilerReference reference;
  public final boolean bindingAttempted;
  public final boolean hasBoundCompilerObject;

  private PartialReparseDryRunIsolatedCompilerBindingResult(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerAcquisition acquisition,
      @NonNull PartialReparseDryRunIsolatedCompilerReference reference,
      boolean bindingAttempted,
      boolean hasBoundCompilerObject) {
    this.state = state;
    this.reason = reason;
    this.acquisition = acquisition;
    this.reference = reference;
    this.bindingAttempted = bindingAttempted;
    this.hasBoundCompilerObject = hasBoundCompilerObject;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerBindingResult notBound(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerBindingResult(
        State.NOT_BOUND,
        reason,
        PartialReparseDryRunIsolatedCompilerAcquisition.notAcquired(reason),
        PartialReparseDryRunIsolatedCompilerReference.notAvailable(reason),
        false,
        false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerBindingResult deferred(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerAcquisition acquisition,
      @NonNull PartialReparseDryRunIsolatedCompilerReference reference) {
    return new PartialReparseDryRunIsolatedCompilerBindingResult(
        State.DEFERRED, reason, acquisition, reference, false, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerBindingResult bound(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerAcquisition acquisition,
      @NonNull PartialReparseDryRunIsolatedCompilerReference reference) {
    return new PartialReparseDryRunIsolatedCompilerBindingResult(
        State.BOUND, reason, acquisition, reference, true, true);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerBindingResult bindingFailed(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerAcquisition acquisition,
      @NonNull PartialReparseDryRunIsolatedCompilerReference reference) {
    return new PartialReparseDryRunIsolatedCompilerBindingResult(
        State.BINDING_FAILED, reason, acquisition, reference, true, false);
  }

  @NonNull
  public PartialReparseDryRunIsolatedCompilerBindingResult cleanupCompleted(@NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedCompilerBindingResult(
        State.CLEANED_UP,
        reason,
        acquisition.cleanupCompleted(reason),
        reference.release(reason),
        true,
        false);
  }

  public boolean isDeferred() {
    return state == State.DEFERRED;
  }

  public boolean isBound() {
    return state == State.BOUND;
  }

  public boolean isBindingFailed() {
    return state == State.BINDING_FAILED;
  }

  public boolean isCleanedUp() {
    return state == State.CLEANED_UP;
  }
}