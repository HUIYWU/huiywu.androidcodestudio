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
import androidx.annotation.Nullable;

/**
 * Describes whether an isolated compiler handle is allowed to expose a copied compiler reference.
 *
 * <p>This model deliberately allows {@link #hasCompilerReference} to stay false even for a CREATED
 * state, so the pipeline can first settle ownership and release semantics before real copied
 * {@link JavaCompilerService} instances participate in dry-run execution.
 */
public final class PartialReparseDryRunIsolatedCompilerReference {

  public enum State {
    NOT_AVAILABLE,
    CREATED,
    RELEASED
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  public final boolean hasCompilerReference;
  @Nullable public final JavaCompilerService compiler;
  public final boolean requiresDestroy;
  public final boolean requiresClose;

  private PartialReparseDryRunIsolatedCompilerReference(
      @NonNull State state,
      @NonNull String reason,
      boolean hasCompilerReference,
      @Nullable JavaCompilerService compiler,
      boolean requiresDestroy,
      boolean requiresClose) {
    this.state = state;
    this.reason = reason;
    this.hasCompilerReference = hasCompilerReference;
    this.compiler = compiler;
    this.requiresDestroy = requiresDestroy;
    this.requiresClose = requiresClose;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerReference notAvailable(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerReference(
        State.NOT_AVAILABLE, reason, false, null, false, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerReference createdWithoutReference(
      @NonNull String reason, boolean requiresDestroy, boolean requiresClose) {
    return new PartialReparseDryRunIsolatedCompilerReference(
        State.CREATED, reason, false, null, requiresDestroy, requiresClose);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerReference created(
      @NonNull String reason,
      @NonNull JavaCompilerService compiler,
      boolean requiresDestroy,
      boolean requiresClose) {
    return new PartialReparseDryRunIsolatedCompilerReference(
        State.CREATED, reason, true, compiler, requiresDestroy, requiresClose);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerReference released(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerReference(
        State.RELEASED, reason, false, null, false, false);
  }

  @NonNull
  public PartialReparseDryRunIsolatedCompilerReference release(@NonNull String reason) {
    if (isReleased()) {
      return this;
    }
    return released(reason);
  }

  public boolean isCreated() {
    return state == State.CREATED;
  }

  public boolean isReleased() {
    return state == State.RELEASED;
  }
}