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
 * Describes how a future isolated dry-run session would assemble a copied compiler instance.
 *
 * <p>This descriptor is still non-executing. Its purpose is to make the copied-service assembly and
 * cleanup contract explicit before any real dry-run session is allowed to create a copied compiler.
 */
public final class PartialReparseDryRunIsolatedSessionAssembly {

  @NonNull public final String reason;
  public final boolean usesJavaCompilerServiceCopyMethod;
  public final boolean reusesLiveModuleReference;
  public final boolean reusesLiveSourceFileManager;
  public final boolean createsFreshReusableCompiler;
  public final boolean startsWithEmptyCachedCompile;
  public final boolean clearsCopiedDiagnostics;
  public final boolean clearsCopiedModificationCache;
  public final boolean requiresExplicitDestroy;

  private PartialReparseDryRunIsolatedSessionAssembly(
      @NonNull String reason,
      boolean usesJavaCompilerServiceCopyMethod,
      boolean reusesLiveModuleReference,
      boolean reusesLiveSourceFileManager,
      boolean createsFreshReusableCompiler,
      boolean startsWithEmptyCachedCompile,
      boolean clearsCopiedDiagnostics,
      boolean clearsCopiedModificationCache,
      boolean requiresExplicitDestroy) {
    this.reason = reason;
    this.usesJavaCompilerServiceCopyMethod = usesJavaCompilerServiceCopyMethod;
    this.reusesLiveModuleReference = reusesLiveModuleReference;
    this.reusesLiveSourceFileManager = reusesLiveSourceFileManager;
    this.createsFreshReusableCompiler = createsFreshReusableCompiler;
    this.startsWithEmptyCachedCompile = startsWithEmptyCachedCompile;
    this.clearsCopiedDiagnostics = clearsCopiedDiagnostics;
    this.clearsCopiedModificationCache = clearsCopiedModificationCache;
    this.requiresExplicitDestroy = requiresExplicitDestroy;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedSessionAssembly fromCopyBlueprint(
      @NonNull PartialReparseDryRunIsolatedCopyBlueprint blueprint,
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedSessionAssembly(
        reason,
        true,
        true,
        blueprint.sharesSourceFileManagerWithLiveCompiler,
        blueprint.requiresFreshReusableCompiler,
        blueprint.cachedCompileMustStartEmpty,
        true,
        true,
        blueprint.requiresExplicitDestroy);
  }
}