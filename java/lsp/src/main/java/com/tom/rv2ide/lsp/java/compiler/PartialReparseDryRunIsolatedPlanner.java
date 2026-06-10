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

/** Plans whether an isolated dry-run partial snapshot can be produced. */
public class PartialReparseDryRunIsolatedPlanner {

  public static final String DEFAULT_NOT_AVAILABLE_REASON =
      "isolated compiler copy is not implemented yet";

  private final PartialReparseDryRunIsolatedCompilerCopyProvider compilerCopyProvider;

  public PartialReparseDryRunIsolatedPlanner() {
    this(new PartialReparseDryRunIsolatedCompilerCopyProvider());
  }

  PartialReparseDryRunIsolatedPlanner(
      @NonNull PartialReparseDryRunIsolatedCompilerCopyProvider compilerCopyProvider) {
    this.compilerCopyProvider = compilerCopyProvider;
  }

  @NonNull
  public PartialReparseDryRunIsolatedPlan plan(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedPlan compilerCopyPlan =
        compilerCopyProvider.planCompilerCopy(request, eligibility, attemptReport);
    if (!compilerCopyPlan.isReady()) {
      return compilerCopyPlan;
    }
    return PartialReparseDryRunIsolatedPlan.ready(compilerCopyPlan.reason);
  }
}