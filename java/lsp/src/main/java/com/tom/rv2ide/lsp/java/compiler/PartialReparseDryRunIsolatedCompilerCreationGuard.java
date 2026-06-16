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
 * Guards whether the isolated dry-run path is even allowed to attempt copied-compiler creation.
 *
 * <p>This seam remains non-executing. Its job is to separate "creation is conceptually allowed" from
 * actually calling {@link JavaCompilerService#copy()} or running any isolated compile work.
 */
public final class PartialReparseDryRunIsolatedCompilerCreationGuard {

  public enum State {
    NOT_ALLOWED,
    ALLOWED
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  public final boolean mayCreateCompilerCopy;
  public final boolean mayMutateLiveCompilerState;

  private PartialReparseDryRunIsolatedCompilerCreationGuard(
      @NonNull State state,
      @NonNull String reason,
      boolean mayCreateCompilerCopy,
      boolean mayMutateLiveCompilerState) {
    this.state = state;
    this.reason = reason;
    this.mayCreateCompilerCopy = mayCreateCompilerCopy;
    this.mayMutateLiveCompilerState = mayMutateLiveCompilerState;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerCreationGuard notAllowed(
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerCreationGuard(State.NOT_ALLOWED, reason, false, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerCreationGuard allowed(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerCreationGuard(State.ALLOWED, reason, true, false);
  }

  public boolean isAllowed() {
    return state == State.ALLOWED;
  }
}