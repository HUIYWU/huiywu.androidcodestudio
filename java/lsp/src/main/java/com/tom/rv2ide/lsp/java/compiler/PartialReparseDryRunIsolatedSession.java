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
 * Represents the lifecycle of an isolated dry-run compiler session.
 *
 * <p>This model intentionally does not expose a real compiler copy yet. Its first job is to make the
 * safety and resource-management requirements explicit before the dry-run path is allowed to execute
 * against any copied compiler state.
 */
public final class PartialReparseDryRunIsolatedSession {

  public enum State {
    NOT_AVAILABLE,
    READY,
    CLOSED
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  public final boolean requiresCompilerCopy;
  @NonNull public final PartialReparseDryRunIsolatedCleanupPlan cleanupPlan;
  public final boolean requiresClose;
  public final boolean mayMutateLiveCompilerState;
  public final boolean sharesSourceFileManagerWithLiveCompiler;
  public final boolean requiresFreshReusableCompiler;
  public final boolean cachedCompileMustStartEmpty;

  private PartialReparseDryRunIsolatedSession(
      @NonNull State state,
      @NonNull String reason,
      boolean requiresCompilerCopy,
      @NonNull PartialReparseDryRunIsolatedCleanupPlan cleanupPlan,
      boolean requiresClose,
      boolean mayMutateLiveCompilerState,
      boolean sharesSourceFileManagerWithLiveCompiler,
      boolean requiresFreshReusableCompiler,
      boolean cachedCompileMustStartEmpty) {
    this.state = state;
    this.reason = reason;
    this.requiresCompilerCopy = requiresCompilerCopy;
    this.cleanupPlan = cleanupPlan;
    this.requiresClose = requiresClose;
    this.mayMutateLiveCompilerState = mayMutateLiveCompilerState;
    this.sharesSourceFileManagerWithLiveCompiler = sharesSourceFileManagerWithLiveCompiler;
    this.requiresFreshReusableCompiler = requiresFreshReusableCompiler;
    this.cachedCompileMustStartEmpty = cachedCompileMustStartEmpty;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSession notAvailable(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedSession(
        State.NOT_AVAILABLE,
        reason,
        true,
        PartialReparseDryRunIsolatedCleanupPlan.notRequired(reason),
        false,
        false,
        false,
        true,
        true);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSession ready(
      @NonNull String reason,
      boolean requiresClose,
      boolean sharesSourceFileManagerWithLiveCompiler,
      boolean requiresFreshReusableCompiler,
      boolean cachedCompileMustStartEmpty) {
    return new PartialReparseDryRunIsolatedSession(
        State.READY,
        reason,
        true,
        PartialReparseDryRunIsolatedCleanupPlan.required(
            reason, false, requiresClose, false, true),
        requiresClose,
        false,
        sharesSourceFileManagerWithLiveCompiler,
        requiresFreshReusableCompiler,
        cachedCompileMustStartEmpty);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSession closed(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedSession(
        State.CLOSED,
        reason,
        true,
        PartialReparseDryRunIsolatedCleanupPlan.completed(reason),
        false,
        false,
        false,
        true,
        true);
  }

  public boolean isReady() {
    return state == State.READY;
  }

  public boolean isClosed() {
    return state == State.CLOSED;
  }
}