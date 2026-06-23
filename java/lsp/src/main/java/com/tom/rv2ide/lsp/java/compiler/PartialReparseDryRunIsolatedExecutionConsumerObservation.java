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
 * Aggregates bridge / consumer / planner-side observation of the non-executing execution path.
 */
public final class PartialReparseDryRunIsolatedExecutionConsumerObservation {

  public enum State {
    NOT_READY,
    DEFERRED,
    READY,
    FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedExecutionAttemptResult executionAttemptResult;
  public final boolean bridgeAttempted;
  public final boolean bridgeFailed;
  public final boolean consumeAttempted;
  public final boolean consumeFailed;
  public final boolean readinessCheckAttempted;
  public final boolean readinessFailed;
  public final boolean nonExecuting;

  private PartialReparseDryRunIsolatedExecutionConsumerObservation(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedExecutionAttemptResult executionAttemptResult,
      boolean bridgeAttempted,
      boolean bridgeFailed,
      boolean consumeAttempted,
      boolean consumeFailed,
      boolean readinessCheckAttempted,
      boolean readinessFailed,
      boolean nonExecuting) {
    this.state = state;
    this.reason = reason;
    this.executionAttemptResult = executionAttemptResult;
    this.bridgeAttempted = bridgeAttempted;
    this.bridgeFailed = bridgeFailed;
    this.consumeAttempted = consumeAttempted;
    this.consumeFailed = consumeFailed;
    this.readinessCheckAttempted = readinessCheckAttempted;
    this.readinessFailed = readinessFailed;
    this.nonExecuting = nonExecuting;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedExecutionConsumerObservation notReady(
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedExecutionConsumerObservation(
        State.NOT_READY,
        reason,
        PartialReparseDryRunIsolatedExecutionAttemptResult.notStarted(reason),
        false,
        false,
        false,
        false,
        false,
        false,
        true);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedExecutionConsumerObservation deferred(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedExecutionAttemptResult executionAttemptResult,
      boolean bridgeAttempted,
      boolean bridgeFailed,
      boolean consumeAttempted,
      boolean consumeFailed,
      boolean nonExecuting) {
    return new PartialReparseDryRunIsolatedExecutionConsumerObservation(
        State.DEFERRED,
        reason,
        executionAttemptResult,
        bridgeAttempted,
        bridgeFailed,
        consumeAttempted,
        consumeFailed,
        false,
        false,
        nonExecuting);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedExecutionConsumerObservation ready(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedExecutionAttemptResult executionAttemptResult,
      boolean bridgeAttempted,
      boolean consumeAttempted,
      boolean nonExecuting) {
    return new PartialReparseDryRunIsolatedExecutionConsumerObservation(
        State.READY,
        reason,
        executionAttemptResult,
        bridgeAttempted,
        false,
        consumeAttempted,
        false,
        true,
        false,
        nonExecuting);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedExecutionConsumerObservation failed(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedExecutionAttemptResult executionAttemptResult,
      boolean bridgeAttempted,
      boolean consumeAttempted) {
    return new PartialReparseDryRunIsolatedExecutionConsumerObservation(
        State.FAILED,
        reason,
        executionAttemptResult,
        bridgeAttempted,
        true,
        consumeAttempted,
        true,
        true,
        true,
        true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedExecutionConsumerObservation cleanupCompleted(
      @NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedExecutionConsumerObservation(
        State.CLEANED_UP,
        reason,
        executionAttemptResult.cleanupCompleted(reason),
        bridgeAttempted,
        bridgeFailed,
        consumeAttempted,
        consumeFailed,
        readinessCheckAttempted,
        readinessFailed,
        nonExecuting);
  }

  public boolean isDeferred() {
    return state == State.DEFERRED;
  }

  public boolean isReady() {
    return state == State.READY;
  }

  public boolean isFailed() {
    return state == State.FAILED;
  }

  public boolean isCleanedUp() {
    return state == State.CLEANED_UP;
  }
}