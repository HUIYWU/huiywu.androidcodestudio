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
 * Legacy compatibility shell describing whether a future upper-layer consumer has accepted the
 * attempt executor bridge.
 *
 * <p>The execution-side observation mainline now lives in
 * {@link PartialReparseDryRunIsolatedExecutionConsumerObservation}. This type remains as a thin
 * facade for older call sites and tests that still expect the nested bridge-shaped contract.
 */
public final class PartialReparseDryRunIsolatedAttemptExecutorConsumerResult {

  public enum State {
    NOT_CONSUMED,
    DEFERRED,
    CONSUMED,
    CONSUME_FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedAttemptExecutorBridge attemptExecutorBridge;
  public final boolean consumeAttempted;
  public final boolean consumeFailed;
  public final boolean nonExecutingConsumer;

  private PartialReparseDryRunIsolatedAttemptExecutorConsumerResult(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedAttemptExecutorBridge attemptExecutorBridge,
      boolean consumeAttempted,
      boolean consumeFailed,
      boolean nonExecutingConsumer) {
    this.state = state;
    this.reason = reason;
    this.attemptExecutorBridge = attemptExecutorBridge;
    this.consumeAttempted = consumeAttempted;
    this.consumeFailed = consumeFailed;
    this.nonExecutingConsumer = nonExecutingConsumer;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedAttemptExecutorConsumerResult notConsumed(
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedAttemptExecutorConsumerResult(
        State.NOT_CONSUMED,
        reason,
        PartialReparseDryRunIsolatedAttemptExecutorBridge.notBridged(reason),
        false,
        false,
        true);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedAttemptExecutorConsumerResult deferred(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedAttemptExecutorBridge attemptExecutorBridge) {
    return new PartialReparseDryRunIsolatedAttemptExecutorConsumerResult(
        State.DEFERRED,
        reason,
        attemptExecutorBridge,
        false,
        false,
        true);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedAttemptExecutorConsumerResult fromObservation(
      @NonNull PartialReparseDryRunIsolatedExecutionConsumerObservation observation) {
    if (!observation.executionAttemptResult.preflightResult.session.isReady()) {
      return notConsumed(observation.reason);
    }
    return deferred(
        observation.reason,
        PartialReparseDryRunIsolatedAttemptExecutorBridge.deferred(
            observation.reason, observation.executionAttemptResult));
  }

  @NonNull
  public static PartialReparseDryRunIsolatedAttemptExecutorConsumerResult consumed(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedAttemptExecutorBridge attemptExecutorBridge,
      boolean nonExecutingConsumer) {
    return new PartialReparseDryRunIsolatedAttemptExecutorConsumerResult(
        State.CONSUMED,
        reason,
        attemptExecutorBridge,
        true,
        false,
        nonExecutingConsumer);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedAttemptExecutorConsumerResult consumeFailed(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedAttemptExecutorBridge attemptExecutorBridge) {
    return new PartialReparseDryRunIsolatedAttemptExecutorConsumerResult(
        State.CONSUME_FAILED,
        reason,
        attemptExecutorBridge,
        true,
        true,
        true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedAttemptExecutorConsumerResult cleanupCompleted(
      @NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedAttemptExecutorConsumerResult(
        State.CLEANED_UP,
        reason,
        attemptExecutorBridge.cleanupCompleted(reason),
        consumeAttempted,
        consumeFailed,
        nonExecutingConsumer);
  }

  public boolean isDeferred() {
    return state == State.DEFERRED;
  }

  public boolean isConsumed() {
    return state == State.CONSUMED;
  }

  public boolean isConsumeFailed() {
    return state == State.CONSUME_FAILED;
  }

  public boolean isCleanedUp() {
    return state == State.CLEANED_UP;
  }
}