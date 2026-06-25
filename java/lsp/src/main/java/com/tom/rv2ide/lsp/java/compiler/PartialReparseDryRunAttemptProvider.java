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
import com.tom.rv2ide.lsp.java.models.CompilationRequest;

/**
 * Provides an optional isolated dry-run partial reparse attempt.
 *
 * <p>The default implementation deliberately returns {@code null}: JavaCompilerService must not call
 * the mutating {@code tryReparse(...)} path from dry-run mode until an isolated copy/snapshot based
 * attempt is implemented. Keeping this provider as a separate seam makes that future wiring explicit
 * while preserving the current safe full-recompile-only behavior.
 */
public final class PartialReparseDryRunAttemptProvider {

  @FunctionalInterface
  public interface AttemptFactory {
    @Nullable
    PartialReparseDryRunVerifier.Attempt create(
        @NonNull CompilationRequest request, @NonNull PartialReparseEligibility eligibility);
  }

  private final AttemptFactory attemptFactory;

  public PartialReparseDryRunAttemptProvider() {
    this((request, eligibility) -> null);
  }

  PartialReparseDryRunAttemptProvider(@NonNull AttemptFactory attemptFactory) {
    this.attemptFactory = attemptFactory;
  }

  @Nullable
  public PartialReparseDryRunVerifier.Attempt createAttempt(
      @NonNull CompilationRequest request, @NonNull PartialReparseEligibility eligibility) {
    return attemptFactory.create(request, eligibility);
  }
}
