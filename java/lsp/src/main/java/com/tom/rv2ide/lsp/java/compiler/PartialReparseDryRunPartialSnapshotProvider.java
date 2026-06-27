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
import jdkx.tools.JavaFileObject;

/**
 * Provides an optional isolated partial snapshot for dry-run correctness comparison.
 *
 * <p>The default implementation deliberately returns {@code null}. A real implementation must run on
 * isolated compiler state, must not commit partial results, and must not mutate the live cached javac
 * AST/Context owned by {@link JavaCompilerService}.
 */
public final class PartialReparseDryRunPartialSnapshotProvider {

  private final PartialReparseDryRunIsolatedPlanner isolatedPlanner;

  public PartialReparseDryRunPartialSnapshotProvider() {
    this(new PartialReparseDryRunIsolatedPlanner());
  }

  PartialReparseDryRunPartialSnapshotProvider(
      @NonNull PartialReparseDryRunIsolatedPlanner isolatedPlanner) {
    this.isolatedPlanner = isolatedPlanner;
  }

  @Nullable
  public PartialReparseDryRunSnapshot createPartialSnapshot(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    return createPartialSnapshot(request, eligibility, attemptReport, null);
  }
  @Nullable
  public PartialReparseDryRunSnapshot createPartialSnapshot(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      @Nullable CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedPlan plan =
        isolatedPlanner.plan(request, eligibility, attemptReport, liveCompiler);
    if (!plan.isReady()) {
      return null;
    }
    if (!(liveCompiler instanceof JavaCompilerService)) {
      return null;
    }
    final JavaCompilerService copiedCompiler = ((JavaCompilerService) liveCompiler).copy();
    try {
      final SynchronizedTask synchronizedTask = copiedCompiler.compile(request);
      return synchronizedTask.get(
          task -> {
            if (task == null || task.task == null || task.roots == null || task.roots.isEmpty()) {
              return null;
            }
            return new PartialReparseDryRunSnapshotCollector()
                .collect(copiedCompiler.diagnostics, task.task.methodPositions);
          });
    } finally {
      copiedCompiler.destroy();
    }
  }
}
