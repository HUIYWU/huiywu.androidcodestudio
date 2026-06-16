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
 * Describes whether a compiler handle is ready to be consumed by higher candidate/session stages.
 *
 * <p>This keeps "a handle exists" separate from "that handle is now considered stably consumable by
 * candidate/session assembly".
 */
public final class PartialReparseDryRunIsolatedCompilerHandleReadyResult {

  public enum State {
    NOT_READY,
    DEFERRED,
    READY,
    READY_FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedCompilerHandle handle;
  public final boolean readyCheckAttempted;
  public final boolean readyFailed;

  private PartialReparseDryRunIsolatedCompilerHandleReadyResult(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedCompilerHandle handle,
      boolean readyCheckAttempted,
      boolean readyFailed) {
    this.state = state;
    this.reason = reason;
    this.handle = handle;
    this.readyCheckAttempted = readyCheckAttempted;
    this.readyFailed = readyFailed;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerHandleReadyResult notReady(
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerHandleReadyResult(
        State.NOT_READY,
        reason,
        PartialReparseDryRunIsolatedCompilerHandle.notAvailable(reason),
        false,
        false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerHandleReadyResult deferred(
      @NonNull String reason, @NonNull PartialReparseDryRunIsolatedCompilerHandle handle) {
    return new PartialReparseDryRunIsolatedCompilerHandleReadyResult(
        State.DEFERRED, reason, handle, false, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerHandleReadyResult ready(
      @NonNull String reason, @NonNull PartialReparseDryRunIsolatedCompilerHandle handle) {
    return new PartialReparseDryRunIsolatedCompilerHandleReadyResult(
        State.READY, reason, handle, true, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerHandleReadyResult readyFailed(
      @NonNull String reason, @NonNull PartialReparseDryRunIsolatedCompilerHandle handle) {
    return new PartialReparseDryRunIsolatedCompilerHandleReadyResult(
        State.READY_FAILED, reason, handle, true, true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedCompilerHandleReadyResult cleanupCompleted(
      @NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedCompilerHandleReadyResult(
        State.CLEANED_UP, reason, handle.release(reason), true, readyFailed);
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