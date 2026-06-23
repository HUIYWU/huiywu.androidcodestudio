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
 * Aggregates the previous session-readiness and executable-preflight observation layers into a
 * single non-executing preflight contract.
 */
public final class PartialReparseDryRunIsolatedSessionExecutionPreflight {

  public enum State {
    NOT_READY,
    DEFERRED,
    READY,
    FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedSession session;
  public final boolean readinessCheckAttempted;
  public final boolean readinessFailed;
  public final boolean preflightAttempted;
  public final boolean preflightFailed;

  private PartialReparseDryRunIsolatedSessionExecutionPreflight(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedSession session,
      boolean readinessCheckAttempted,
      boolean readinessFailed,
      boolean preflightAttempted,
      boolean preflightFailed) {
    this.state = state;
    this.reason = reason;
    this.session = session;
    this.readinessCheckAttempted = readinessCheckAttempted;
    this.readinessFailed = readinessFailed;
    this.preflightAttempted = preflightAttempted;
    this.preflightFailed = preflightFailed;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSessionExecutionPreflight notReady(
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedSessionExecutionPreflight(
        State.NOT_READY,
        reason,
        PartialReparseDryRunIsolatedSession.notAvailable(reason),
        false,
        false,
        false,
        false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSessionExecutionPreflight deferred(
      @NonNull String reason, @NonNull PartialReparseDryRunIsolatedSession session) {
    return new PartialReparseDryRunIsolatedSessionExecutionPreflight(
        State.DEFERRED,
        reason,
        session,
        false,
        false,
        false,
        false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSessionExecutionPreflight ready(
      @NonNull String reason, @NonNull PartialReparseDryRunIsolatedSession session) {
    return new PartialReparseDryRunIsolatedSessionExecutionPreflight(
        State.READY,
        reason,
        session,
        true,
        false,
        true,
        false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSessionExecutionPreflight failed(
      @NonNull String reason, @NonNull PartialReparseDryRunIsolatedSession session) {
    return new PartialReparseDryRunIsolatedSessionExecutionPreflight(
        State.FAILED,
        reason,
        session,
        true,
        true,
        true,
        true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedSessionExecutionPreflight cleanupCompleted(
      @NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedSessionExecutionPreflight(
        State.CLEANED_UP,
        reason,
        PartialReparseDryRunIsolatedSession.closed(reason),
        true,
        readinessFailed,
        true,
        preflightFailed);
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
