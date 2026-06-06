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
 * Result of a real partial reparse attempt.
 *
 * <p>This is separate from {@link PartialReparseDecision}: the decision says whether the request is
 * allowed to try partial reparse; this result says whether the actual AST rewrite succeeded. Keeping
 * both concepts separate makes fallback reasons explicit once the feature flag is enabled.
 */
public final class PartialReparseAttemptResult {

  public enum Status {
    SUCCESS,
    NOT_APPLICABLE,
    FAILED
  }

  public final Status status;
  public final String reason;

  private PartialReparseAttemptResult(Status status, String reason) {
    this.status = status;
    this.reason = reason;
  }

  public static PartialReparseAttemptResult success(String reason) {
    return new PartialReparseAttemptResult(Status.SUCCESS, reason);
  }

  public static PartialReparseAttemptResult notApplicable(String reason) {
    return new PartialReparseAttemptResult(Status.NOT_APPLICABLE, reason);
  }

  public static PartialReparseAttemptResult failed(String reason) {
    return new PartialReparseAttemptResult(Status.FAILED, reason);
  }

  public boolean isSuccess() {
    return status == Status.SUCCESS;
  }
}