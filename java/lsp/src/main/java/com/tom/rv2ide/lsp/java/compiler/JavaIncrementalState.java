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
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent;
import com.tom.rv2ide.models.Position;
import com.tom.rv2ide.models.Range;

/**
 * Tracks editor deltas used by the Java LSP partial reparse experiment.
 *
 * <p>This state is intentionally separated from {@link JavaCompilerService} so the incremental
 * path has an explicit lifecycle. Full recompilation remains the stable default path; this object
 * only preserves the information needed to decide and execute a future method-body reparse.
 */
public final class JavaIncrementalState {

  private int changeDelta = 0;
  private Position lastReparsePosition = Position.NONE;
  private Position newCursorPosition = Position.NONE;
  @Nullable private Range latestChangeRange;

  public void onDocumentChange(@NonNull DocumentChangeEvent event) {
    this.changeDelta += event.getChangeDelta();
    this.latestChangeRange = event.getChangeRange();
    this.newCursorPosition = event.getChangeRange().getEnd();
  }

  public int getChangeDelta() {
    return changeDelta;
  }

  public Position getNewCursorPosition() {
    return newCursorPosition;
  }

  @Nullable
  public Range getLatestChangeRange() {
    return latestChangeRange;
  }

  public boolean isChangeValidForReparse() {
    return this.lastReparsePosition == Position.NONE
        || (this.newCursorPosition != Position.NONE
            && this.lastReparsePosition.getLine() == this.newCursorPosition.getLine());
  }

  public void markReparseSucceeded() {
    this.changeDelta = 0;
    this.lastReparsePosition = this.newCursorPosition;
  }

  public void resetAfterFullRecompile() {
    this.changeDelta = 0;
    this.lastReparsePosition = Position.NONE;
    this.newCursorPosition = Position.NONE;
    this.latestChangeRange = null;
  }

  public void resetForCopy() {
    this.changeDelta = 0;
    this.lastReparsePosition = Position.NONE;
    this.newCursorPosition = Position.NONE;
    this.latestChangeRange = null;
  }
}