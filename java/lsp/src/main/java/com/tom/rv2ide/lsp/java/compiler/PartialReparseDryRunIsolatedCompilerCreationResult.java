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
 * Describes the result of attempting to create an isolated copied compiler.
 *
 * <p>This seam still allows the default pipeline to remain non-executing. It exists so future
 * implementations can model success, failure and mandatory cleanup without immediately running a
 * real isolated compile.
 */
public final class PartialReparseDryRunIsolatedCompilerCreationResult {

  public enum State {
    NOT_CREATED,
    CREATED,
    FAILED,
    CLEANED_UP
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  public final boolean hasCreatedCompiler;
  @Nullable public final JavaCompilerService compiler;
  public final boolean requiresDestroy;
  public final boolean requiresClose;
  public final boolean cleanupRequired;
  public final boolean cleanupCompleted;

  private PartialReparseDryRunIsolatedCompilerCreationResult(
      @NonNull State state,
      @NonNull String reason,
      boolean hasCreatedCompiler,
      @Nullable JavaCompilerService compiler,
      boolean requiresDestroy,
      boolean requiresClose,
      boolean cleanupRequired,
      boolean cleanupCompleted) {
    this.state = state;
    this.reason = reason;
    this.hasCreatedCompiler = hasCreatedCompiler;
    this.compiler = compiler;
    this.requiresDestroy = requiresDestroy;
    this.requiresClose = requiresClose;
    this.cleanupRequired = cleanupRequired;
    this.cleanupCompleted = cleanupCompleted;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerCreationResult notCreated(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerCreationResult(
        State.NOT_CREATED, reason, false, null, false, false, false, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerCreationResult createdWithoutCompiler(
      @NonNull String reason, boolean requiresDestroy, boolean requiresClose) {
    return new PartialReparseDryRunIsolatedCompilerCreationResult(
        State.CREATED, reason, false, null, requiresDestroy, requiresClose, false, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerCreationResult created(
      @NonNull String reason,
      @NonNull JavaCompilerService compiler,
      boolean requiresDestroy,
      boolean requiresClose) {
    return new PartialReparseDryRunIsolatedCompilerCreationResult(
        State.CREATED, reason, true, compiler, requiresDestroy, requiresClose, false, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerCreationResult failed(
      @NonNull String reason, boolean cleanupRequired) {
    return new PartialReparseDryRunIsolatedCompilerCreationResult(
        State.FAILED, reason, false, null, false, false, cleanupRequired, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerCreationResult cleanedUp(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerCreationResult(
        State.CLEANED_UP, reason, false, null, false, false, false, true);
  }

  @NonNull
  public PartialReparseDryRunIsolatedCompilerCreationResult cleanupCompleted(@NonNull String reason) {
    if (state == State.CLEANED_UP) {
      return this;
    }
    return cleanedUp(reason);
  }

  public boolean isCreated() {
    return state == State.CREATED;
  }

  public boolean isFailed() {
    return state == State.FAILED;
  }

  public boolean isCleanedUp() {
    return state == State.CLEANED_UP;
  }
}
