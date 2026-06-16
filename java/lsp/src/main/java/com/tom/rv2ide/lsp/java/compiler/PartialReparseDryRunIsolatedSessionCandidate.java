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
 * Represents a non-executing candidate for an isolated dry-run compiler session.
 *
 * <p>This is the first seam allowed to model a future copied {@link JavaCompilerService} session as a
 * concrete lifecycle participant, while still deliberately avoiding any real compile/reparse work.
 */
public final class PartialReparseDryRunIsolatedSessionCandidate {

  public enum State {
    NOT_AVAILABLE,
    CREATED,
    CLOSED
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  public final boolean hasCompilerCopyCandidate;
  @NonNull public final PartialReparseDryRunIsolatedCleanupPlan cleanupPlan;
  public final boolean requiresDestroy;
  public final boolean requiresClose;
  public final boolean canExecuteDryRun;
  public final boolean sharesSourceFileManagerWithLiveCompiler;
  public final boolean requiresFreshReusableCompiler;
  public final boolean cachedCompileMustStartEmpty;

  private PartialReparseDryRunIsolatedSessionCandidate(
      @NonNull State state,
      @NonNull String reason,
      boolean hasCompilerCopyCandidate,
      @NonNull PartialReparseDryRunIsolatedCleanupPlan cleanupPlan,
      boolean requiresDestroy,
      boolean requiresClose,
      boolean canExecuteDryRun,
      boolean sharesSourceFileManagerWithLiveCompiler,
      boolean requiresFreshReusableCompiler,
      boolean cachedCompileMustStartEmpty) {
    this.state = state;
    this.reason = reason;
    this.hasCompilerCopyCandidate = hasCompilerCopyCandidate;
    this.cleanupPlan = cleanupPlan;
    this.requiresDestroy = requiresDestroy;
    this.requiresClose = requiresClose;
    this.canExecuteDryRun = canExecuteDryRun;
    this.sharesSourceFileManagerWithLiveCompiler = sharesSourceFileManagerWithLiveCompiler;
    this.requiresFreshReusableCompiler = requiresFreshReusableCompiler;
    this.cachedCompileMustStartEmpty = cachedCompileMustStartEmpty;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSessionCandidate notAvailable(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedSessionCandidate(
        State.NOT_AVAILABLE,
        reason,
        false,
        PartialReparseDryRunIsolatedCleanupPlan.notRequired(reason),
        false,
        false,
        false,
        false,
        true,
        true);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSessionCandidate created(
      @NonNull String reason,
      boolean requiresDestroy,
      boolean requiresClose,
      boolean sharesSourceFileManagerWithLiveCompiler,
      boolean requiresFreshReusableCompiler,
      boolean cachedCompileMustStartEmpty) {
    return new PartialReparseDryRunIsolatedSessionCandidate(
        State.CREATED,
        reason,
        true,
        PartialReparseDryRunIsolatedCleanupPlan.required(
            reason, requiresDestroy, requiresClose, false, true),
        requiresDestroy,
        requiresClose,
        false,
        sharesSourceFileManagerWithLiveCompiler,
        requiresFreshReusableCompiler,
        cachedCompileMustStartEmpty);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSessionCandidate closed(
      @NonNull String reason,
      boolean sharesSourceFileManagerWithLiveCompiler,
      boolean requiresFreshReusableCompiler,
      boolean cachedCompileMustStartEmpty) {
    return new PartialReparseDryRunIsolatedSessionCandidate(
        State.CLOSED,
        reason,
        false,
        PartialReparseDryRunIsolatedCleanupPlan.completed(reason),
        false,
        false,
        false,
        sharesSourceFileManagerWithLiveCompiler,
        requiresFreshReusableCompiler,
        cachedCompileMustStartEmpty);
  }

  @NonNull
  public PartialReparseDryRunIsolatedSessionCandidate close(@NonNull String reason) {
    if (isClosed()) {
      return this;
    }
    return closed(
        reason,
        sharesSourceFileManagerWithLiveCompiler,
        requiresFreshReusableCompiler,
        cachedCompileMustStartEmpty);
  }

  public boolean isCreated() {
    return state == State.CREATED;
  }

  public boolean isClosed() {
    return state == State.CLOSED;
  }
}
