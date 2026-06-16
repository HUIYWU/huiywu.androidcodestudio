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
 * Describes whether a session candidate is ready to be consumed by session assembly.
 *
 * <p>This keeps "a candidate exists" separate from "that candidate is now considered stably ready
 * for session creation".
 */
public final class PartialReparseDryRunIsolatedSessionCandidateReadyResult {

  public enum State {
    NOT_READY,
    DEFERRED,
    READY,
    READY_FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  @NonNull public final PartialReparseDryRunIsolatedSessionCandidate candidate;
  public final boolean readyCheckAttempted;
  public final boolean readyFailed;

  private PartialReparseDryRunIsolatedSessionCandidateReadyResult(
      @NonNull State state,
      @NonNull String reason,
      @NonNull PartialReparseDryRunIsolatedSessionCandidate candidate,
      boolean readyCheckAttempted,
      boolean readyFailed) {
    this.state = state;
    this.reason = reason;
    this.candidate = candidate;
    this.readyCheckAttempted = readyCheckAttempted;
    this.readyFailed = readyFailed;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSessionCandidateReadyResult notReady(
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedSessionCandidateReadyResult(
        State.NOT_READY,
        reason,
        PartialReparseDryRunIsolatedSessionCandidate.notAvailable(reason),
        false,
        false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSessionCandidateReadyResult deferred(
      @NonNull String reason, @NonNull PartialReparseDryRunIsolatedSessionCandidate candidate) {
    return new PartialReparseDryRunIsolatedSessionCandidateReadyResult(
        State.DEFERRED, reason, candidate, false, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSessionCandidateReadyResult ready(
      @NonNull String reason, @NonNull PartialReparseDryRunIsolatedSessionCandidate candidate) {
    return new PartialReparseDryRunIsolatedSessionCandidateReadyResult(
        State.READY, reason, candidate, true, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSessionCandidateReadyResult readyFailed(
      @NonNull String reason, @NonNull PartialReparseDryRunIsolatedSessionCandidate candidate) {
    return new PartialReparseDryRunIsolatedSessionCandidateReadyResult(
        State.READY_FAILED, reason, candidate, true, true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedSessionCandidateReadyResult cleanupCompleted(
      @NonNull String reason) {
    if (isCleanedUp()) {
      return this;
    }
    return new PartialReparseDryRunIsolatedSessionCandidateReadyResult(
        State.CLEANED_UP, reason, candidate.close(reason), true, readyFailed);
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