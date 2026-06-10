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

/** Describes whether dry-run may create an isolated partial snapshot. */
public final class PartialReparseDryRunIsolatedPlan {

  public enum State {
    NOT_AVAILABLE,
    READY
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  public final boolean requiresCompilerCopy;
  public final boolean mayMutateLiveCompilerState;

  private PartialReparseDryRunIsolatedPlan(
      @NonNull State state,
      @NonNull String reason,
      boolean requiresCompilerCopy,
      boolean mayMutateLiveCompilerState) {
    this.state = state;
    this.reason = reason;
    this.requiresCompilerCopy = requiresCompilerCopy;
    this.mayMutateLiveCompilerState = mayMutateLiveCompilerState;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedPlan notAvailable(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedPlan(State.NOT_AVAILABLE, reason, true, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedPlan ready(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedPlan(State.READY, reason, true, false);
  }

  public boolean isReady() {
    return state == State.READY;
  }
}