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
 * Describes whether a dry-run partial snapshot can obtain an isolated compiler copy.
 *
 * <p>The default implementation is intentionally conservative. Although {@link JavaCompilerService}
 * already has a {@code copy()} helper, this provider must remain unavailable until the dry-run path
 * has a proven execution strategy that cannot mutate the live cached javac AST/Context and can safely
 * close any borrowed compiler resources.
 */
public class PartialReparseDryRunIsolatedCompilerCopyProvider {

  public static final String DEFAULT_NOT_AVAILABLE_REASON =
      "isolated compiler copy provider is not implemented yet";

  @NonNull
  public PartialReparseDryRunIsolatedPlan planCompilerCopy(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    return PartialReparseDryRunIsolatedPlan.notAvailable(DEFAULT_NOT_AVAILABLE_REASON);
  }
}
