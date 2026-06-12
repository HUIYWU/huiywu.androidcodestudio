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
 * Owns a copied {@link JavaCompilerService} resource candidate without executing any dry-run work.
 *
 * <p>This seam exists to model resource ownership, explicit destroy/close obligations and idempotent
 * release before the isolated dry-run pipeline is allowed to compile against a copied compiler.
 */
public final class PartialReparseDryRunIsolatedCompilerHandle {

  public enum State {
    NOT_AVAILABLE,
    CREATED,
    RELEASED
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  public final boolean hasCompilerCopy;
  public final boolean requiresDestroy;
  public final boolean requiresClose;
  public final boolean sharesSourceFileManagerWithLiveCompiler;
  public final boolean requiresFreshReusableCompiler;
  public final boolean cachedCompileMustStartEmpty;

  private PartialReparseDryRunIsolatedCompilerHandle(
      @NonNull State state,
      @NonNull String reason,
      boolean hasCompilerCopy,
      boolean requiresDestroy,
      boolean requiresClose,
      boolean sharesSourceFileManagerWithLiveCompiler,
      boolean requiresFreshReusableCompiler,
      boolean cachedCompileMustStartEmpty) {
    this.state = state;
    this.reason = reason;
    this.hasCompilerCopy = hasCompilerCopy;
    this.requiresDestroy = requiresDestroy;
    this.requiresClose = requiresClose;
    this.sharesSourceFileManagerWithLiveCompiler = sharesSourceFileManagerWithLiveCompiler;
    this.requiresFreshReusableCompiler = requiresFreshReusableCompiler;
    this.cachedCompileMustStartEmpty = cachedCompileMustStartEmpty;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerHandle notAvailable(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerHandle(
        State.NOT_AVAILABLE, reason, false, false, false, false, true, true);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerHandle created(
      @NonNull String reason,
      boolean requiresDestroy,
      boolean requiresClose,
      boolean sharesSourceFileManagerWithLiveCompiler,
      boolean requiresFreshReusableCompiler,
      boolean cachedCompileMustStartEmpty) {
    return new PartialReparseDryRunIsolatedCompilerHandle(
        State.CREATED,
        reason,
        true,
        requiresDestroy,
        requiresClose,
        sharesSourceFileManagerWithLiveCompiler,
        requiresFreshReusableCompiler,
        cachedCompileMustStartEmpty);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerHandle released(
      @NonNull String reason,
      boolean sharesSourceFileManagerWithLiveCompiler,
      boolean requiresFreshReusableCompiler,
      boolean cachedCompileMustStartEmpty) {
    return new PartialReparseDryRunIsolatedCompilerHandle(
        State.RELEASED,
        reason,
        false,
        false,
        false,
        sharesSourceFileManagerWithLiveCompiler,
        requiresFreshReusableCompiler,
        cachedCompileMustStartEmpty);
  }

  @NonNull
  public PartialReparseDryRunIsolatedCompilerHandle release(@NonNull String reason) {
    if (isReleased()) {
      return this;
    }
    return released(
        reason,
        sharesSourceFileManagerWithLiveCompiler,
        requiresFreshReusableCompiler,
        cachedCompileMustStartEmpty);
  }

  public boolean isCreated() {
    return state == State.CREATED;
  }

  public boolean isReleased() {
    return state == State.RELEASED;
  }
}