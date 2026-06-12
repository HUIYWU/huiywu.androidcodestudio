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
import com.tom.rv2ide.lsp.java.models.CompilationRequest;

/**
 * Creates isolated dry-run session descriptors.
 *
 * <p>The default implementation stays conservative: even though {@link JavaCompilerService#copy()}
 * exists, we still need an explicit lifecycle contract before any copied compiler can participate in
 * dry-run partial snapshot generation.
 */
public class PartialReparseDryRunIsolatedSessionFactory {

  public static final String DEFAULT_NOT_AVAILABLE_REASON =
      "isolated dry-run session factory is not implemented yet";

  @NonNull
  public PartialReparseDryRunIsolatedSession createSession(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    return PartialReparseDryRunIsolatedSession.notAvailable(DEFAULT_NOT_AVAILABLE_REASON);
  }
}