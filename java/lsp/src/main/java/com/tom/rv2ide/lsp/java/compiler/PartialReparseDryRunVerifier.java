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
 * Verifies dry-run partial reparse attempts without committing their result.
 *
 * <p>The invariant of this class is intentionally strict: the stable full-recompile path is always
 * executed, regardless of whether the dry-run attempt succeeds, fails, is absent, or throws. This
 * gives {@link JavaCompilerService} a safe seam for future isolated experiments while preserving the
 * user-visible result on the full-recompile path.
 */
public final class PartialReparseDryRunVerifier {

  @FunctionalInterface
  public interface Attempt {
    @NonNull
    PartialReparseAttemptResult run() throws Throwable;
  }

  @FunctionalInterface
  public interface Observer {
    void onDryRun(@Nullable PartialReparseAttemptResult result, @Nullable Throwable error);
  }

  @NonNull
  public PartialReparseDryRunReport verifyThenFullRecompile(
      @Nullable Attempt attempt, @NonNull Runnable fullRecompile, @NonNull Observer observer) {
    if (attempt == null) {
      fullRecompile.run();
      return PartialReparseDryRunReport.notCreated();
    }

    final PartialReparseAttemptResult result;
    try {
      result = attempt.run();
    } catch (final Throwable error) {
      observer.onDryRun(null, error);
      fullRecompile.run();
      return PartialReparseDryRunReport.exception(error);
    }

    observer.onDryRun(result, null);
    fullRecompile.run();
    return PartialReparseDryRunReport.fromAttemptResult(result);
  }
}