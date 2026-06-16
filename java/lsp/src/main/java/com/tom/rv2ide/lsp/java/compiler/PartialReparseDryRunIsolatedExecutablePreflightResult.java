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

/** Final preflight gate before any future executable isolated dry-run attempt may begin. */
public final class PartialReparseDryRunIsolatedExecutablePreflightResult {

  public enum State {
    NOT_READY,
    DEFERRED,
    READY,
    PRECHECK_FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedSessionReadinessResult sessionReadinessResult;
  public final boolean preflightAttempted;
  public final boolean preflightFailed;

  private PartialReparseDryRunIsolatedExecutablePreflightResult(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedSessionReadinessResult sessionReadinessResult,
      boolean preflightAttempted,
      boolean preflightFailed) {
    this.state = state;
    this.reason = reason;
    this.sessionReadinessResult = sessionReadinessResult;
    this.preflightAttempted = preflightAttempted;
    this.preflightFailed = preflightFailed;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedExecutablePreflightResult notReady(
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedExecutablePreflightResult(
        State.NOT_READY,
        reason,
        PartialReparseDryRunIsolatedSessionReadinessResult.notReady(reason),
        false,
        false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedExecutablePreflightResult deferred(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedSessionReadinessResult sessionReadinessResult) {
    return new PartialReparseDryRunIsolatedExecutablePreflightResult(
        State.DEFERRED, reason, sessionReadinessResult, false, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedExecutablePreflightResult ready(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedSessionReadinessResult sessionReadinessResult) {
    return new PartialReparseDryRunIsolatedExecutablePreflightResult(
        State.READY, reason, sessionReadinessResult, true, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedExecutablePreflightResult precheckFailed(
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedSessionReadinessResult sessionReadinessResult) {
    return new PartialReparseDryRunIsolatedExecutablePreflightResult(
        State.PRECHECK_FAILED, reason, sessionReadinessResult, true, true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedExecutablePreflightResult cleanupCompleted(
      @NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedExecutablePreflightResult(
        State.CLEANED_UP,
        reason,
        sessionReadinessResult.cleanupCompleted(reason),
        true,
        preflightFailed);
  }

  public boolean isDeferred() {
    return state == State.DEFERRED;
  }

  public boolean isReady() {
    return state == State.READY;
  }

  public boolean isPrecheckFailed() {
    return state == State.PRECHECK_FAILED;
  }

  public boolean isCleanedUp() {
    return state == State.CLEANED_UP;
  }
}