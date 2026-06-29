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
 * Routes a partial reparse decision to the appropriate execution branch.
 *
 * <p>This class intentionally contains no javac state. It gives the Java compiler service a small,
 * testable seam for verifying the entry-level full-recompile / try-partial routing without
 * constructing a real {@link JavaCompilerService} or mutating cached javac AST state.
 */
public final class PartialReparseRouter {

  public enum Branch {
    FULL_RECOMPILE,
    TRY_PARTIAL_REPARSE
  }

  public Branch route(
      @NonNull PartialReparseDecision decision,
      @NonNull Runnable fullRecompile,
      @NonNull Runnable tryPartialReparse) {
    switch (decision.action) {
      case TRY_PARTIAL_REPARSE:
        tryPartialReparse.run();
        return Branch.TRY_PARTIAL_REPARSE;
      case FULL_RECOMPILE:
      default:
        fullRecompile.run();
        return Branch.FULL_RECOMPILE;
    }
  }
}
