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
 * Executes the final partial reparse call after preflight checks have accepted the edit.
 *
 * <p>This class intentionally does not know about javac task state. It owns only the small,
 * testable boundary between JavaCompilerService and the lower-level PartialReparser: extracting the
 * replacement method body, invoking the provided attempt callback, and mapping callback outcomes to
 * {@link PartialReparseAttemptResult}.
 */
public final class PartialReparseExecutor {

  @FunctionalInterface
  public interface Attempt {
    boolean reparse(@NonNull String newBody) throws Exception;
  }

  @NonNull
  public PartialReparseAttemptResult execute(
      @NonNull CharSequence contents, int bodyStart, int bodyEnd, @NonNull Attempt attempt) {
    if (bodyStart < 0 || bodyEnd < bodyStart || bodyEnd > contents.length()) {
      return PartialReparseAttemptResult.failed("method body range is outside document contents");
    }

    final String newBody = contents.subSequence(bodyStart, bodyEnd).toString();
    final boolean reparsed;
    try {
      reparsed = attempt.reparse(newBody);
    } catch (final Exception err) {
      return PartialReparseAttemptResult.failed("PartialReparser.reparseMethod threw exception");
    }

    if (!reparsed) {
      return PartialReparseAttemptResult.failed("PartialReparser.reparseMethod returned false");
    }

    return PartialReparseAttemptResult.success("method body reparsed");
  }
}
