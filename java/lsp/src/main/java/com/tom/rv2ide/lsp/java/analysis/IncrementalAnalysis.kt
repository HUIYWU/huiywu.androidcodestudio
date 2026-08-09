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

package com.tom.rv2ide.lsp.java.analysis

import com.tom.rv2ide.projects.models.DocumentSnapshotIdentity
import com.tom.rv2ide.projects.models.OneHopDocumentEdit

/** The Java semantic operation requesting an analysis plan. */
enum class IncrementalAnalysisRequest {
  COMPLETION,
  SIGNATURE,
  DIAGNOSTICS,
}

/** Classification supplied by a syntax-aware caller; UNKNOWN is the required safe default. */
enum class IncrementalChangeClass {
  WHITESPACE_OR_COMMENT,
  EXPRESSION_OR_STATEMENT,
  MEMBER_BODY,
  MEMBER_DECLARATION,
  TYPE_STRUCTURE,
  FILE_STRUCTURE,
  WHOLE_DOCUMENT_REPLACEMENT,
  UNKNOWN,
}

enum class IncrementalAnalysisPlanKind {
  FULL,
  REUSE_STABLE,
  CANDIDATE,
}

data class IncrementalAnalysisPlan(
    val kind: IncrementalAnalysisPlanKind,
    val reason: Reason,
) {
  enum class Reason {
    DIAGNOSTICS_REQUIRE_FULL,
    NO_VERIFIED_EDIT,
    TARGET_IDENTITY_MISMATCH,
    ENVIRONMENT_CHANGED,
    MULTI_SOURCE,
    KOTLIN_ABI_OR_SYNTHETIC_INPUT,
    UNKNOWN_CHANGE,
    STRUCTURAL_CHANGE,
    VERIFIED_TRIVIAL_EDIT,
    VERIFIED_CANDIDATE_EDIT,
  }
}

/**
 * Pure, side-effect-free plan selection. It never creates compiler state and never authorizes
 * mutation of a stable semantic snapshot. The caller must supply a syntax classification that it
 * has independently verified; absence of that proof is a full fallback.
 */
object IncrementalAnalysisPlanner {
  fun plan(
      request: IncrementalAnalysisRequest,
      current: DocumentSnapshotIdentity,
      edit: OneHopDocumentEdit?,
      changeClass: IncrementalChangeClass,
      environmentGenerationMatches: Boolean,
      singleSource: Boolean,
      kotlinAbiOrSyntheticInput: Boolean,
  ): IncrementalAnalysisPlan {
    if (request == IncrementalAnalysisRequest.DIAGNOSTICS) {
      return full(IncrementalAnalysisPlan.Reason.DIAGNOSTICS_REQUIRE_FULL)
    }
    if (!environmentGenerationMatches) {
      return full(IncrementalAnalysisPlan.Reason.ENVIRONMENT_CHANGED)
    }
    if (!singleSource) {
      return full(IncrementalAnalysisPlan.Reason.MULTI_SOURCE)
    }
    if (kotlinAbiOrSyntheticInput) {
      return full(IncrementalAnalysisPlan.Reason.KOTLIN_ABI_OR_SYNTHETIC_INPUT)
    }
    if (edit == null) {
      return full(IncrementalAnalysisPlan.Reason.NO_VERIFIED_EDIT)
    }
    if (edit.target != current) {
      return full(IncrementalAnalysisPlan.Reason.TARGET_IDENTITY_MISMATCH)
    }
    return when (changeClass) {
      IncrementalChangeClass.WHITESPACE_OR_COMMENT ->
          IncrementalAnalysisPlan(
              IncrementalAnalysisPlanKind.REUSE_STABLE,
              IncrementalAnalysisPlan.Reason.VERIFIED_TRIVIAL_EDIT,
          )
      IncrementalChangeClass.EXPRESSION_OR_STATEMENT,
      IncrementalChangeClass.MEMBER_BODY ->
          IncrementalAnalysisPlan(
              IncrementalAnalysisPlanKind.CANDIDATE,
              IncrementalAnalysisPlan.Reason.VERIFIED_CANDIDATE_EDIT,
          )
      IncrementalChangeClass.MEMBER_DECLARATION,
      IncrementalChangeClass.TYPE_STRUCTURE,
      IncrementalChangeClass.FILE_STRUCTURE,
      IncrementalChangeClass.WHOLE_DOCUMENT_REPLACEMENT ->
          full(IncrementalAnalysisPlan.Reason.STRUCTURAL_CHANGE)
      IncrementalChangeClass.UNKNOWN -> full(IncrementalAnalysisPlan.Reason.UNKNOWN_CHANGE)
    }
  }

  private fun full(reason: IncrementalAnalysisPlan.Reason) =
      IncrementalAnalysisPlan(IncrementalAnalysisPlanKind.FULL, reason)
}
