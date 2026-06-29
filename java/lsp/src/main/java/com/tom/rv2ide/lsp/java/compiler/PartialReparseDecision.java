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

/** Decision produced before routing a Java compilation request through full compile or partial reparse. */
public final class PartialReparseDecision {
  public enum Action {
    FULL_RECOMPILE,
    TRY_PARTIAL_REPARSE
  }


  public final Action action;
  public final String reason;

  private PartialReparseDecision(Action action, String reason) {
    this.action = action;
    this.reason = reason;
  }

  public static PartialReparseDecision fullRecompile(String reason) {
    return new PartialReparseDecision(Action.FULL_RECOMPILE, reason);
  }

  public static PartialReparseDecision tryPartial(String reason) {
    return new PartialReparseDecision(Action.TRY_PARTIAL_REPARSE, reason);
  }

}

