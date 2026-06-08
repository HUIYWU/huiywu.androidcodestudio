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
import com.tom.rv2ide.models.Range;
import java.util.List;
import openjdk.source.util.TreePath;

/**
 * Locates the method range that contains a cursor offset.
 *
 * <p>The method position list is expected to be sorted by start offset, as produced by
 * {@link MethodRangeScanner}. Keeping this lookup outside {@link JavaCompilerService} makes the
 * cursor-to-method routing behavior independently testable.
 */
public final class PartialReparseMethodLocator {

  @Nullable
  public Pair<Range, TreePath> findCurrentMethod(
      @NonNull final List<Pair<Range, TreePath>> positions, final long cursor) {
    int left = 0;
    int right = positions.size() - 1;
    while (left <= right) {
      final int mid = (left + right) / 2;
      final Pair<Range, TreePath> method = positions.get(mid);
      final Range range = method.first;
      final int startIndex = range.getStart().requireIndex();
      final int endIndex = range.getEnd().requireIndex();

      if (cursor < startIndex) {
        right = mid - 1;
      } else if (cursor > endIndex) {
        left = mid + 1;
      } else {
        return method;
      }
    }
    return null;
  }
}