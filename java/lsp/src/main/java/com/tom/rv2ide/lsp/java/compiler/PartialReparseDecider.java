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
import com.tom.rv2ide.lsp.java.models.CompilationRequest;
import com.tom.rv2ide.preferences.internal.JavaPreferences;

/**
 * Conservative router for partial reparse. This class intentionally rejects most requests unless
 * the incremental path is explicitly enabled by feature flags.
 */
public class PartialReparseDecider {

  public PartialReparseDecision decide(@NonNull PartialReparseEligibility eligibility) {
    final CompilationRequest request = eligibility.request;
    if (request == null) {
      return PartialReparseDecision.fullRecompile("request is null");
    }

    if (eligibility.needsRecompilation) {
      return PartialReparseDecision.fullRecompile("cached compile is missing or closed");
    }

    if (!eligibility.hasPartialRequest) {
      return PartialReparseDecision.fullRecompile("no partial request");
    }

    if (eligibility.sourceCount != 1) {
      return PartialReparseDecision.fullRecompile("partial reparse requires exactly one source");
    }

    if (eligibility.cursor < 0) {
      return PartialReparseDecision.fullRecompile("invalid partial reparse cursor");
    }

    if (eligibility.contentsLength < 0) {
      return PartialReparseDecision.fullRecompile("partial reparse contents is null");
    }

    if (!eligibility.changeValidForReparse) {
      return PartialReparseDecision.fullRecompile("document change is not valid for partial reparse");
    }

    if (eligibility.latestChangeRange == null) {
      return PartialReparseDecision.fullRecompile("latest document change range is unknown");
    }

    if (!eligibility.changeDeltaWithinLimit) {
      return PartialReparseDecision.fullRecompile("document change delta is too large for partial reparse");
    }

    if (!isPartialReparseEnabledByUser()) {
      return PartialReparseDecision.fullRecompile("partial reparse disabled in Java editor preferences");
    }

    if (!isPartialReparseFeatureEnabled() && !isPartialReparseDryRunEnabled()) {
      return PartialReparseDecision.fullRecompile("partial reparse disabled by feature flags");
    }

    if (isPartialReparseDryRunEnabled()) {
      return PartialReparseDecision.dryRun("partial reparse dry-run enabled");
    }

    return PartialReparseDecision.tryPartial("eligible for partial reparse");
  }

  protected boolean isPartialReparseEnabledByUser() {
    return JavaPreferences.INSTANCE.isJavaIncrementalReparseEnabled();
  }

  protected boolean isPartialReparseFeatureEnabled() {
    return JavaLspFeatureFlags.ENABLE_PARTIAL_REPARSE;
  }

  protected boolean isPartialReparseDryRunEnabled() {
    return JavaLspFeatureFlags.ENABLE_PARTIAL_REPARSE_DRY_RUN;
  }
}

