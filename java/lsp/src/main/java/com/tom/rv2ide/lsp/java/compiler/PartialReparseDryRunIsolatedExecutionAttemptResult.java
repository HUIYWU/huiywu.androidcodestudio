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

/** Describes whether an isolated execution attempt has started, deferred, failed or cleaned up. */
public final class PartialReparseDryRunIsolatedExecutionAttemptResult {

  public enum State {
    NOT_STARTED,
    DEFERRED,
    STARTED,
    ATTEMPT_FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedSessionExecutionPreflight preflightResult;

  public final boolean attemptStarted;
  public final boolean attemptFailed;
  public final boolean nonExecutingStub;
  private PartialReparseDryRunIsolatedExecutionAttemptResult(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedSessionExecutionPreflight preflightResult,
      boolean attemptStarted,
      boolean attemptFailed,
      boolean nonExecutingStub) {

    this.state = state;
    this.reason = reason;
    this.preflightResult = preflightResult;
    this.attemptStarted = attemptStarted;
    this.attemptFailed = attemptFailed;
    this.nonExecutingStub = nonExecutingStub;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedExecutionAttemptResult notStarted(
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedExecutionAttemptResult(
        State.NOT_STARTED,
        reason,
        PartialReparseDryRunIsolatedSessionExecutionPreflight.notReady(reason),

        false,
        false,
        true);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedExecutionAttemptResult deferred(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedSessionExecutionPreflight preflightResult) {

    return new PartialReparseDryRunIsolatedExecutionAttemptResult(
        State.DEFERRED,
        reason,
        preflightResult,
        false,
        false,
        true);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedExecutionAttemptResult started(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedSessionExecutionPreflight preflightResult,
      boolean nonExecutingStub) {

    return new PartialReparseDryRunIsolatedExecutionAttemptResult(
        State.STARTED,
        reason,
        preflightResult,
        true,
        false,
        nonExecutingStub);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedExecutionAttemptResult attemptFailed(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedSessionExecutionPreflight preflightResult) {

    return new PartialReparseDryRunIsolatedExecutionAttemptResult(
        State.ATTEMPT_FAILED,
        reason,
        preflightResult,
        true,
        true,
        true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedExecutionAttemptResult cleanupCompleted(
      @NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedExecutionAttemptResult(
        State.CLEANED_UP,
        reason,
        preflightResult.cleanupCompleted(reason),
        attemptStarted,
        attemptFailed,
        nonExecutingStub);
  }

  public boolean isDeferred() {
    return state == State.DEFERRED;
  }

  public boolean isStarted() {
    return state == State.STARTED;
  }

  public boolean isAttemptFailed() {
    return state == State.ATTEMPT_FAILED;
  }

  public boolean isCleanedUp() {
    return state == State.CLEANED_UP;
  }
}