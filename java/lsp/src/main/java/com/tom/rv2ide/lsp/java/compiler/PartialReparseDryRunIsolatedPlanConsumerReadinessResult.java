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
 * Describes whether planner-side plan consumers are ready to accept the execution-side consumer
 * contract, while still remaining non-executing.
 */
public final class PartialReparseDryRunIsolatedPlanConsumerReadinessResult {

  public enum State {
    NOT_READY,
    DEFERRED,
    READY,
    READY_FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedAttemptExecutorConsumerResult attemptExecutorConsumerResult;
  public final boolean readinessCheckAttempted;
  public final boolean readinessFailed;
  public final boolean nonExecutingPlanConsumer;

  private PartialReparseDryRunIsolatedPlanConsumerReadinessResult(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedAttemptExecutorConsumerResult attemptExecutorConsumerResult,
      boolean readinessCheckAttempted,
      boolean readinessFailed,
      boolean nonExecutingPlanConsumer) {
    this.state = state;
    this.reason = reason;
    this.attemptExecutorConsumerResult = attemptExecutorConsumerResult;
    this.readinessCheckAttempted = readinessCheckAttempted;
    this.readinessFailed = readinessFailed;
    this.nonExecutingPlanConsumer = nonExecutingPlanConsumer;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedPlanConsumerReadinessResult notReady(
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedPlanConsumerReadinessResult(
        State.NOT_READY,
        reason,
        PartialReparseDryRunIsolatedAttemptExecutorConsumerResult.notConsumed(reason),
        false,
        false,
        true);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedPlanConsumerReadinessResult deferred(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedAttemptExecutorConsumerResult attemptExecutorConsumerResult) {
    return new PartialReparseDryRunIsolatedPlanConsumerReadinessResult(
        State.DEFERRED,
        reason,
        attemptExecutorConsumerResult,
        false,
        false,
        true);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedPlanConsumerReadinessResult ready(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedAttemptExecutorConsumerResult attemptExecutorConsumerResult,
      boolean nonExecutingPlanConsumer) {
    return new PartialReparseDryRunIsolatedPlanConsumerReadinessResult(
        State.READY,
        reason,
        attemptExecutorConsumerResult,
        true,
        false,
        nonExecutingPlanConsumer);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedPlanConsumerReadinessResult readyFailed(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedAttemptExecutorConsumerResult attemptExecutorConsumerResult) {
    return new PartialReparseDryRunIsolatedPlanConsumerReadinessResult(
        State.READY_FAILED,
        reason,
        attemptExecutorConsumerResult,
        true,
        true,
        true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedPlanConsumerReadinessResult cleanupCompleted(
      @NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedPlanConsumerReadinessResult(
        State.CLEANED_UP,
        reason,
        attemptExecutorConsumerResult.cleanupCompleted(reason),
        readinessCheckAttempted,
        readinessFailed,
        nonExecutingPlanConsumer);
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