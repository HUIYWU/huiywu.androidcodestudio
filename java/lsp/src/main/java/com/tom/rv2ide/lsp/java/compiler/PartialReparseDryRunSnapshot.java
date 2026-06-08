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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable dry-run snapshot used for comparing isolated partial results with full recompile. */
public final class PartialReparseDryRunSnapshot {

  @NonNull public final List<String> diagnostics;
  @NonNull public final List<String> methodPositionKeys;
  @NonNull public final List<String> sourcePositionKeys;

  public PartialReparseDryRunSnapshot(
      @NonNull List<String> diagnostics,
      @NonNull List<String> methodPositionKeys,
      @NonNull List<String> sourcePositionKeys) {
    this.diagnostics = immutableCopy(diagnostics);
    this.methodPositionKeys = immutableCopy(methodPositionKeys);
    this.sourcePositionKeys = immutableCopy(sourcePositionKeys);
  }

  @NonNull
  public static PartialReparseDryRunSnapshot empty() {
    return new PartialReparseDryRunSnapshot(
        Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
  }

  @NonNull
  private static List<String> immutableCopy(@NonNull List<String> values) {
    return Collections.unmodifiableList(new ArrayList<>(values));
  }
}
