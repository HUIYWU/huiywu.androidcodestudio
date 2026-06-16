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
 * Describes who owns cleanup obligations for a copied compiler lifecycle stage.
 *
 * <p>This remains a non-executing contract: it only models whether destroy/close/failure cleanup are
 * required before future isolated execution is allowed to become real.
 */
public final class PartialReparseDryRunIsolatedCleanupPlan {

  public enum State {
    NOT_REQUIRED,
    REQUIRED,
    COMPLETED
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  public final boolean requiresDestroy;
  public final boolean requiresClose;
  public final boolean requiresFailureCleanup;
  public final boolean cleanupOwnedBySession;

  private PartialReparseDryRunIsolatedCleanupPlan(
      @NonNull State state,
      @NonNull String reason,
      boolean requiresDestroy,
      boolean requiresClose,
      boolean requiresFailureCleanup,
      boolean cleanupOwnedBySession) {
    this.state = state;
    this.reason = reason;
    this.requiresDestroy = requiresDestroy;
    this.requiresClose = requiresClose;
    this.requiresFailureCleanup = requiresFailureCleanup;
    this.cleanupOwnedBySession = cleanupOwnedBySession;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCleanupPlan notRequired(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedCleanupPlan(
        State.NOT_REQUIRED, reason, false, false, false, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCleanupPlan required(
      @NonNull String reason,
      boolean requiresDestroy,
      boolean requiresClose,
      boolean requiresFailureCleanup,
      boolean cleanupOwnedBySession) {
    return new PartialReparseDryRunIsolatedCleanupPlan(
        State.REQUIRED,
        reason,
        requiresDestroy,
        requiresClose,
        requiresFailureCleanup,
        cleanupOwnedBySession);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCleanupPlan completed(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedCleanupPlan(
        State.COMPLETED, reason, false, false, false, false);
  }

  @NonNull
  public PartialReparseDryRunIsolatedCleanupPlan complete(@NonNull String reason) {
    if (isCompleted()) {
      return this;
    }
    return completed(reason);
  }

  public boolean isRequired() {
    return state == State.REQUIRED;
  }

  public boolean isCompleted() {
    return state == State.COMPLETED;
  }
}