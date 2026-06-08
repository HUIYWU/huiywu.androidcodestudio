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
import androidx.core.util.Pair;
import com.tom.rv2ide.models.Position;
import com.tom.rv2ide.models.Range;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jdkx.tools.Diagnostic;
import jdkx.tools.JavaFileObject;
import openjdk.source.util.TreePath;

/**
 * Collects stable pure-data dry-run snapshots from compiler-observable state.
 *
 * <p>This class deliberately serializes javac-facing objects into strings and does not retain AST,
 * Context, TreePath, or Diagnostic instances. It is therefore safe to use as a seam for future
 * isolated dry-run comparisons.
 */
public final class PartialReparseDryRunSnapshotCollector {

  @NonNull
  public PartialReparseDryRunSnapshot collect(
      @Nullable List<Diagnostic<? extends JavaFileObject>> diagnostics,
      @Nullable Map<String, List<Pair<Range, TreePath>>> methodPositions) {
    return new PartialReparseDryRunSnapshot(
        collectDiagnosticKeys(diagnostics),
        collectMethodPositionKeys(methodPositions),
        collectSourcePositionKeys(methodPositions));
  }

  @NonNull
  List<String> collectDiagnosticKeys(@Nullable List<Diagnostic<? extends JavaFileObject>> diagnostics) {
    if (diagnostics == null || diagnostics.isEmpty()) {
      return Collections.emptyList();
    }
    final List<String> keys = new ArrayList<>();
    for (final Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
      keys.add(toDiagnosticKey(diagnostic));
    }
    Collections.sort(keys);
    return keys;
  }

  @NonNull
  List<String> collectMethodPositionKeys(
      @Nullable Map<String, List<Pair<Range, TreePath>>> methodPositions) {
    if (methodPositions == null || methodPositions.isEmpty()) {
      return Collections.emptyList();
    }
    final List<String> keys = new ArrayList<>();
    for (final Map.Entry<String, List<Pair<Range, TreePath>>> entry : methodPositions.entrySet()) {
      final String path = entry.getKey();
      final List<Pair<Range, TreePath>> ranges = entry.getValue();
      if (ranges == null) {
        continue;
      }
      for (final Pair<Range, TreePath> rangeAndPath : ranges) {
        if (rangeAndPath == null || rangeAndPath.first == null) {
          continue;
        }
        keys.add(path + "|" + toRangeKey(rangeAndPath.first));
      }
    }
    Collections.sort(keys);
    return keys;
  }

  @NonNull
  List<String> collectSourcePositionKeys(
      @Nullable Map<String, List<Pair<Range, TreePath>>> methodPositions) {
    if (methodPositions == null || methodPositions.isEmpty()) {
      return Collections.emptyList();
    }
    final List<String> keys = new ArrayList<>();
    for (final Map.Entry<String, List<Pair<Range, TreePath>>> entry : methodPositions.entrySet()) {
      final List<Pair<Range, TreePath>> ranges = entry.getValue();
      if (ranges == null) {
        continue;
      }
      for (final Pair<Range, TreePath> rangeAndPath : ranges) {
        if (rangeAndPath == null || rangeAndPath.first == null) {
          continue;
        }
        keys.add(toRangeKey(rangeAndPath.first));
      }
    }
    Collections.sort(keys);
    return keys;
  }

  @NonNull
  private static String toDiagnosticKey(@Nullable Diagnostic<? extends JavaFileObject> diagnostic) {
    if (diagnostic == null) {
      return "<null-diagnostic>";
    }
    return sourceKey(diagnostic.getSource())
        + "|"
        + diagnostic.getKind()
        + "|"
        + diagnostic.getCode()
        + "|line="
        + diagnostic.getLineNumber()
        + "|column="
        + diagnostic.getColumnNumber()
        + "|start="
        + diagnostic.getStartPosition()
        + "|end="
        + diagnostic.getEndPosition()
        + "|message="
        + diagnostic.getMessage(Locale.ROOT);
  }

  @NonNull
  private static String sourceKey(@Nullable JavaFileObject source) {
    return source == null ? "<no-source>" : String.valueOf(source.toUri());
  }

  @NonNull
  private static String toRangeKey(@NonNull Range range) {
    return toPositionKey(range.getStart()) + "-" + toPositionKey(range.getEnd());
  }

  @NonNull
  private static String toPositionKey(@NonNull Position position) {
    return position.getLine() + ":" + position.getColumn() + ":" + position.getIndex();
  }
}