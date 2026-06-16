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

/**
 * Represents a slot that may eventually hold a real copied compiler object.
 *
 * <p>The slot lets the lifecycle distinguish between "creation was allowed", "an object slot was
 * allocated", and "a real compiler instance is actually present".
 */
public final class PartialReparseDryRunIsolatedCompilerSlot {

  public enum State {
    NOT_ALLOCATED,
    RESERVED,
    FILLED,
    RELEASED
  }

  @NonNull public final State state;
  @NonNull public final String reason;
  public final boolean hasReservedSlot;
  public final boolean hasCompilerObject;
  @Nullable public final JavaCompilerService compiler;
  public final boolean ownedBySession;

  private PartialReparseDryRunIsolatedCompilerSlot(
      @NonNull State state,
      @NonNull String reason,
      boolean hasReservedSlot,
      boolean hasCompilerObject,
      @Nullable JavaCompilerService compiler,
      boolean ownedBySession) {
    this.state = state;
    this.reason = reason;
    this.hasReservedSlot = hasReservedSlot;
    this.hasCompilerObject = hasCompilerObject;
    this.compiler = compiler;
    this.ownedBySession = ownedBySession;
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerSlot notAllocated(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerSlot(State.NOT_ALLOCATED, reason, false, false, null, false);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerSlot reserved(
      @NonNull String reason, boolean ownedBySession) {
    return new PartialReparseDryRunIsolatedCompilerSlot(State.RESERVED, reason, true, false, null, ownedBySession);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerSlot filled(
      @NonNull String reason,
      @NonNull JavaCompilerService compiler,
      boolean ownedBySession) {
    return new PartialReparseDryRunIsolatedCompilerSlot(State.FILLED, reason, true, true, compiler, ownedBySession);
  }

  @NonNull
  public static PartialReparseDryRunIsolatedCompilerSlot released(@NonNull String reason) {
    return new PartialReparseDryRunIsolatedCompilerSlot(State.RELEASED, reason, false, false, null, false);
  }

  @NonNull
  public PartialReparseDryRunIsolatedCompilerSlot release(@NonNull String reason) {
    if (isReleased()) {
      return this;
    }
    return released(reason);
  }

  public boolean isReserved() {
    return state == State.RESERVED;
  }

  public boolean isFilled() {
    return state == State.FILLED;
  }

  public boolean isReleased() {
    return state == State.RELEASED;
  }
}