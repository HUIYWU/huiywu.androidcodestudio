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
 * Handles fallback after a partial reparse attempt.
 *
 * <p>This class contains no javac state. It gives the compiler service a small, testable seam for
 * verifying that successful partial reparse attempts do not trigger full recompilation, while
 * not-applicable, failed and exceptional attempts do trigger the stable full-recompile fallback.
 */
public final class PartialReparseFallbackHandler {

  public enum Outcome {
    SUCCESS,
    FALLBACK_AFTER_NOT_APPLICABLE,
    FALLBACK_AFTER_FAILED,
    FALLBACK_AFTER_EXCEPTION
  }

  @FunctionalInterface
  public interface Attempt {
    @NonNull
    PartialReparseAttemptResult run() throws Throwable;
  }

  @FunctionalInterface
  public interface FallbackObserver {
    void onFallback(@Nullable PartialReparseAttemptResult result, @Nullable Throwable error);
  }

  public Outcome handle(
      @NonNull Attempt attempt,
      @NonNull Runnable fullRecompile,
      @NonNull FallbackObserver fallbackObserver) {
    final PartialReparseAttemptResult result;
    try {
      result = attempt.run();
    } catch (Throwable error) {
      fallbackObserver.onFallback(null, error);
      fullRecompile.run();
      return Outcome.FALLBACK_AFTER_EXCEPTION;
    }

    if (result.isSuccess()) {
      return Outcome.SUCCESS;
    }

    fallbackObserver.onFallback(result, null);
    fullRecompile.run();
    if (result.status == PartialReparseAttemptResult.Status.NOT_APPLICABLE) {
      return Outcome.FALLBACK_AFTER_NOT_APPLICABLE;
    }
    return Outcome.FALLBACK_AFTER_FAILED;
  }
}
