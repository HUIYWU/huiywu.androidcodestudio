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

import com.tom.rv2ide.lsp.java.models.CompilationRequest;

/**
 * Conservative router for partial reparse. This class intentionally rejects most requests unless
 * the incremental path is explicitly enabled by feature flags.
 */
public final class PartialReparseDecider {

  public PartialReparseDecision decide(
      CompilationRequest request, boolean needsRecompilation, boolean isChangeValidForReparse) {
    if (request == null) {
      return PartialReparseDecision.fullRecompile("request is null");
    }

    if (needsRecompilation) {
      return PartialReparseDecision.fullRecompile("cached compile is missing or closed");
    }

    if (request.partialRequest == null) {
      return PartialReparseDecision.fullRecompile("no partial request");
    }

    if (request.sources == null || request.sources.size() != 1) {
      return PartialReparseDecision.fullRecompile("partial reparse requires exactly one source");
    }

    if (request.partialRequest.cursor < 0) {
      return PartialReparseDecision.fullRecompile("invalid partial reparse cursor");
    }

    if (request.partialRequest.contents == null) {
      return PartialReparseDecision.fullRecompile("partial reparse contents is null");
    }

    if (!isChangeValidForReparse) {
      return PartialReparseDecision.fullRecompile("document change is not valid for partial reparse");
    }

    if (!JavaLspFeatureFlags.ENABLE_PARTIAL_REPARSE
        && !JavaLspFeatureFlags.ENABLE_PARTIAL_REPARSE_DRY_RUN) {
      return PartialReparseDecision.fullRecompile("partial reparse disabled");
    }

    if (JavaLspFeatureFlags.ENABLE_PARTIAL_REPARSE_DRY_RUN) {
      return PartialReparseDecision.dryRun("partial reparse dry-run enabled");
    }

    return PartialReparseDecision.tryPartial("eligible for partial reparse");
  }
}
