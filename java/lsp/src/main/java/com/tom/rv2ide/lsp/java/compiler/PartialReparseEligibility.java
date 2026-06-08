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
import com.tom.rv2ide.lsp.java.models.PartialReparseRequest;
import com.tom.rv2ide.models.Position;
import com.tom.rv2ide.models.Range;

/**
 * Immutable snapshot of the inputs used to decide whether a Java compilation request may use
 * partial reparse.
 *
 * <p>The decider intentionally consumes this snapshot instead of directly poking into
 * {@link JavaCompilerService}. This keeps the incremental decision observable and makes future
 * tests/logging independent from the mutable compiler service lifecycle.
 */
public final class PartialReparseEligibility {

  @Nullable public final CompilationRequest request;
  public final boolean needsRecompilation;
  public final boolean changeValidForReparse;
  public final boolean changeDeltaWithinLimit;
  public final int sourceCount;
  public final boolean hasPartialRequest;
  public final long cursor;
  public final int contentsLength;
  public final int changeDelta;
  @Nullable public final Position newCursorPosition;
  @Nullable public final Range latestChangeRange;

  private PartialReparseEligibility(
      @Nullable CompilationRequest request,
      boolean needsRecompilation,
      boolean changeValidForReparse,
      boolean changeDeltaWithinLimit,
      int sourceCount,
      boolean hasPartialRequest,
      long cursor,
      int contentsLength,
      int changeDelta,
      @Nullable Position newCursorPosition,
      @Nullable Range latestChangeRange) {
    this.request = request;
    this.needsRecompilation = needsRecompilation;
    this.changeValidForReparse = changeValidForReparse;
    this.changeDeltaWithinLimit = changeDeltaWithinLimit;
    this.sourceCount = sourceCount;
    this.hasPartialRequest = hasPartialRequest;
    this.cursor = cursor;
    this.contentsLength = contentsLength;
    this.changeDelta = changeDelta;
    this.newCursorPosition = newCursorPosition;
    this.latestChangeRange = latestChangeRange;
  }

  public static PartialReparseEligibility from(
      @Nullable CompilationRequest request,
      boolean needsRecompilation,
      @NonNull JavaIncrementalState incrementalState) {
    final PartialReparseRequest partialRequest = request == null ? null : request.partialRequest;
    final int sourceCount =
        request == null || request.sources == null ? -1 : request.sources.size();
    final long cursor = partialRequest == null ? -1 : partialRequest.cursor;
    final int contentsLength =
        partialRequest == null || partialRequest.contents == null
            ? -1
            : partialRequest.contents.length();
    final int changeDelta = incrementalState.getChangeDelta();
    final boolean changeDeltaWithinLimit =
        Math.abs((long) changeDelta) <= JavaLspFeatureFlags.MAX_PARTIAL_REPARSE_CHANGE_DELTA;

    return new PartialReparseEligibility(
        request,
        needsRecompilation,
        incrementalState.isChangeValidForReparse(),
        changeDeltaWithinLimit,
        sourceCount,
        partialRequest != null,
        cursor,
        contentsLength,
        changeDelta,
        incrementalState.getNewCursorPosition(),
        incrementalState.getLatestChangeRange());
  }
}