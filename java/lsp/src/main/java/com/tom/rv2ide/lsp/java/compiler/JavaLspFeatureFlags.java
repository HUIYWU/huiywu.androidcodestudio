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

/**
 * Feature flags for Java LSP incremental compilation/reparse experiments.
 *
 * <p>Keep partial reparse disabled by default. The current production-safe path is still full
 * recompilation; these flags make the incremental path explicit, observable and easy to roll back.
 */
public final class JavaLspFeatureFlags {

  /**
   * Enable real partial reparse result usage.
   *
   * <p>Keep this off until diagnostics-grade correctness is considered production-ready for the
   * shared incremental path. Completion currently participates in the same routing/observation chain
   * but still carries completion-specific input adaptation noise.
   */
  public static final boolean ENABLE_PARTIAL_REPARSE = true;

  /**
   * Enable partial reparse experiment logging path without changing user-visible compilation result.
   *
   * <p>This remains the recommended default while validating diagnostics and completion together
   * before promoting the shared path to real partial-result usage.
   */
  public static final boolean ENABLE_PARTIAL_REPARSE_DRY_RUN = false;

  /** Emit decision/fallback logs for partial reparse routing. */
  public static final boolean ENABLE_PARTIAL_REPARSE_LOGGING = true;

  /**
   * Conservative maximum absolute text delta allowed for partial reparse attempts.
   *
   * <p>Large edits are more likely to cross method/class/import boundaries. Keep them on the stable
   * full-recompile path until a stronger structural diff guard exists.
   */
  public static final int MAX_PARTIAL_REPARSE_CHANGE_DELTA = 500;

  private JavaLspFeatureFlags() {}
}
