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
 * Describes whether an isolated session has reached the final readiness stage before any future
 * executable dry-run behavior is allowed.
 */
public final class PartialReparseDryRunIsolatedSessionReadinessResult {

  public enum State {
    NOT_READY,
    DEFERRED,
    READY,
    READY_FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedSession session;
  public final boolean readinessCheckAttempted;
  public final boolean readinessFailed;

  private PartialReparseDryRunIsolatedSessionReadinessResult(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedSession session,
      boolean readinessCheckAttempted,
      boolean readinessFailed) {
    this.state = state;
    this.reason = reason;
    this.session = session;
    this.readinessCheckAttempted = readinessCheckAttempted;
    this.readinessFailed = readinessFailed;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSessionReadinessResult notReady(
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedSessionReadinessResult(
        State.NOT_READY,
        reason,
        PartialReparseDryRunIsolatedSession.notAvailable(reason),
        false,
        false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSessionReadinessResult deferred(
      @NonNull String reason, @NonNull PartialReparseDryRunIsolatedSession session) {
    return new PartialReparseDryRunIsolatedSessionReadinessResult(
        State.DEFERRED, reason, session, false, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSessionReadinessResult ready(
      @NonNull String reason, @NonNull PartialReparseDryRunIsolatedSession session) {
    return new PartialReparseDryRunIsolatedSessionReadinessResult(
        State.READY, reason, session, true, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSessionReadinessResult readyFailed(
      @NonNull String reason, @NonNull PartialReparseDryRunIsolatedSession session) {
    return new PartialReparseDryRunIsolatedSessionReadinessResult(
        State.READY_FAILED, reason, session, true, true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedSessionReadinessResult cleanupCompleted(
      @NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedSessionReadinessResult(
        State.CLEANED_UP, reason, PartialReparseDryRunIsolatedSession.closed(reason), true, readinessFailed);
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