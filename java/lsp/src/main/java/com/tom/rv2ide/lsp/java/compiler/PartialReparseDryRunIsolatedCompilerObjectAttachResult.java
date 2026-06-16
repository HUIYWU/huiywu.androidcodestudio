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
 * Describes whether a filled copied compiler object was attached into a reference-ready stage.
 *
 * <p>This keeps "slot fill succeeded" separate from "reference now actually carries that object".
 */
public final class PartialReparseDryRunIsolatedCompilerObjectAttachResult {

  public enum State {
    NOT_ATTACHED,
    DEFERRED,
    ATTACHED,
    ATTACH_FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedCompilerObjectFill objectFill;
  @NonNull public final PartialReparseDryRunIsolatedCompilerReference reference;
  public final boolean attachAttempted;
  public final boolean attachFailed;

  private PartialReparseDryRunIsolatedCompilerObjectAttachResult(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerObjectFill objectFill,
      @NonNull PartialReparseDryRunIsolatedCompilerReference reference,
      boolean attachAttempted,
      boolean attachFailed) {
    this.state = state;
    this.reason = reason;
    this.objectFill = objectFill;
    this.reference = reference;
    this.attachAttempted = attachAttempted;
    this.attachFailed = attachFailed;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerObjectAttachResult notAttached(
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerObjectAttachResult(
        State.NOT_ATTACHED,
        reason,
        PartialReparseDryRunIsolatedCompilerObjectFill.notFilled(reason),
        PartialReparseDryRunIsolatedCompilerReference.notAvailable(reason),
        false,
        false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerObjectAttachResult deferred(
      @NonNull String reason, @NonNull PartialReparseDryRunIsolatedCompilerObjectFill objectFill) {
    return new PartialReparseDryRunIsolatedCompilerObjectAttachResult(
        State.DEFERRED,
        reason,
        objectFill,
        PartialReparseDryRunIsolatedCompilerReference.createdWithoutReference(reason, true, true),
        false,
        false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerObjectAttachResult attached(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerObjectFill objectFill,
      @NonNull PartialReparseDryRunIsolatedCompilerReference reference) {
    return new PartialReparseDryRunIsolatedCompilerObjectAttachResult(
        State.ATTACHED, reason, objectFill, reference, true, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerObjectAttachResult attachFailed(
      @NonNull String reason, @NonNull PartialReparseDryRunIsolatedCompilerObjectFill objectFill) {
    return new PartialReparseDryRunIsolatedCompilerObjectAttachResult(
        State.ATTACH_FAILED,
        reason,
        objectFill,
        PartialReparseDryRunIsolatedCompilerReference.notAvailable(reason),
        true,
        true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedCompilerObjectAttachResult cleanupCompleted(
      @NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedCompilerObjectAttachResult(
        State.CLEANED_UP,
        reason,
        objectFill.cleanupCompleted(reason),
        reference.release(reason),
        true,
        attachFailed);
  }

  public boolean isDeferred() {
    return state == State.DEFERRED;
  }

  public boolean isAttached() {
    return state == State.ATTACHED;
  }

  public boolean isAttachFailed() {
    return state == State.ATTACH_FAILED;
  }

  public boolean isCleanedUp() {
    return state == State.CLEANED_UP;
  }
}