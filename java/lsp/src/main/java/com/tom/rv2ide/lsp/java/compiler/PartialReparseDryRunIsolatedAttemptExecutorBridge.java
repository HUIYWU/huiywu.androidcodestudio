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
 * Bridge contract between isolated execution-attempt modeling and a future executor implementation.
 */
public final class PartialReparseDryRunIsolatedAttemptExecutorBridge {

  public enum State {
    NOT_BRIDGED,
    DEFERRED,
    BRIDGED,
    BRIDGE_FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedExecutionAttemptResult executionAttemptResult;
  public final boolean bridgeAttempted;
  public final boolean bridgeFailed;
  public final boolean nonExecutingBridge;

  private PartialReparseDryRunIsolatedAttemptExecutorBridge(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedExecutionAttemptResult executionAttemptResult,
      boolean bridgeAttempted,
      boolean bridgeFailed,
      boolean nonExecutingBridge) {
    this.state = state;
    this.reason = reason;
    this.executionAttemptResult = executionAttemptResult;
    this.bridgeAttempted = bridgeAttempted;
    this.bridgeFailed = bridgeFailed;
    this.nonExecutingBridge = nonExecutingBridge;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedAttemptExecutorBridge notBridged(
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedAttemptExecutorBridge(
        State.NOT_BRIDGED,
        reason,
        PartialReparseDryRunIsolatedExecutionAttemptResult.notStarted(reason),
        false,
        false,
        true);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedAttemptExecutorBridge deferred(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedExecutionAttemptResult executionAttemptResult) {
    return new PartialReparseDryRunIsolatedAttemptExecutorBridge(
        State.DEFERRED,
        reason,
        executionAttemptResult,
        false,
        false,
        true);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedAttemptExecutorBridge bridged(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedExecutionAttemptResult executionAttemptResult,
      boolean nonExecutingBridge) {
    return new PartialReparseDryRunIsolatedAttemptExecutorBridge(
        State.BRIDGED,
        reason,
        executionAttemptResult,
        true,
        false,
        nonExecutingBridge);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedAttemptExecutorBridge bridgeFailed(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedExecutionAttemptResult executionAttemptResult) {
    return new PartialReparseDryRunIsolatedAttemptExecutorBridge(
        State.BRIDGE_FAILED,
        reason,
        executionAttemptResult,
        true,
        true,
        true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedAttemptExecutorBridge cleanupCompleted(
      @NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedAttemptExecutorBridge(
        State.CLEANED_UP,
        reason,
        executionAttemptResult.cleanupCompleted(reason),
        bridgeAttempted,
        bridgeFailed,
        nonExecutingBridge);
  }

  public boolean isDeferred() {
    return state == State.DEFERRED;
  }

  public boolean isBridged() {
    return state == State.BRIDGED;
  }

  public boolean isBridgeFailed() {
    return state == State.BRIDGE_FAILED;
  }

  public boolean isCleanedUp() {
    return state == State.CLEANED_UP;
  }
}