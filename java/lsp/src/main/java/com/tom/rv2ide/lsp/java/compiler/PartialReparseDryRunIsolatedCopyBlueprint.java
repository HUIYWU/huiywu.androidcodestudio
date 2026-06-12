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
 * Blueprint describing how a future isolated dry-run session would obtain a copied compiler.
 *
 * <p>This is intentionally descriptive only. It does not create or own a real
 * {@link JavaCompilerService} copy yet; instead it records the contract that a safe copied compiler
 * session must satisfy before dry-run partial snapshot execution is allowed to use it.
 */
public final class PartialReparseDryRunIsolatedCopyBlueprint {

  @NonNull public final String reason;
  public final boolean sharesSourceFileManagerWithLiveCompiler;
  public final boolean requiresFreshReusableCompiler;
  public final boolean cachedCompileMustStartEmpty;
  public final boolean requiresExplicitDestroy;

  private PartialReparseDryRunIsolatedCopyBlueprint(
      @NonNull String reason,
      boolean sharesSourceFileManagerWithLiveCompiler,
      boolean requiresFreshReusableCompiler,
      boolean cachedCompileMustStartEmpty,
      boolean requiresExplicitDestroy) {
    this.reason = reason;
    this.sharesSourceFileManagerWithLiveCompiler = sharesSourceFileManagerWithLiveCompiler;
    this.requiresFreshReusableCompiler = requiresFreshReusableCompiler;
    this.cachedCompileMustStartEmpty = cachedCompileMustStartEmpty;
    this.requiresExplicitDestroy = requiresExplicitDestroy;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCopyBlueprint fromJavaCompilerServiceCopy(
      @NonNull String reason) {
    return new PartialReparseDryRunIsolatedCopyBlueprint(reason, true, true, true, true);
  }
}