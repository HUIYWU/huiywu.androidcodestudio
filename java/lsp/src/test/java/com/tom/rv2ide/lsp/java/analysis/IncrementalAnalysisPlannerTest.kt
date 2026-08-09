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
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IncrementalAnalysisPlannerTest {
  private val file = Paths.get("/workspace/Test.java")
  private val current = DocumentSnapshotIdentity(file, version = 2, revision = 2L)
  private val edit =
      OneHopDocumentEdit(
          base = DocumentSnapshotIdentity(file, version = 1, revision = 1L),
          target = current,
          baseStartIndex = 10,
          baseEndIndex = 10,
          removedText = "",
          replacementText = "x",
          kind = OneHopDocumentEdit.Kind.INSERT,
      )

  @Test
  fun verifiedWhitespaceEditMayReuseStableSnapshotForInteractiveRequest() {
    val plan = plan(IncrementalAnalysisRequest.COMPLETION, IncrementalChangeClass.WHITESPACE_OR_COMMENT)
    assertEquals(IncrementalAnalysisPlanKind.REUSE_STABLE, plan.kind)
    assertEquals(IncrementalAnalysisPlan.Reason.VERIFIED_TRIVIAL_EDIT, plan.reason)
  }

  @Test
  fun expressionEditIsOnlyACandidateAndNeverAnExecutionDecision() {
    val plan = plan(IncrementalAnalysisRequest.COMPLETION, IncrementalChangeClass.EXPRESSION_OR_STATEMENT)
    assertEquals(IncrementalAnalysisPlanKind.CANDIDATE, plan.kind)
    assertEquals(IncrementalAnalysisPlan.Reason.VERIFIED_CANDIDATE_EDIT, plan.reason)
  }

  @Test
  fun diagnosticsAlwaysUseFullPlan() {
    val plan = plan(IncrementalAnalysisRequest.DIAGNOSTICS, IncrementalChangeClass.EXPRESSION_OR_STATEMENT)
    assertEquals(IncrementalAnalysisPlanKind.FULL, plan.kind)
    assertEquals(IncrementalAnalysisPlan.Reason.DIAGNOSTICS_REQUIRE_FULL, plan.reason)
  }

  @Test
  fun missingProofOrInvalidEnvironmentUsesFullPlan() {
    val noEdit =
        IncrementalAnalysisPlanner.plan(
            IncrementalAnalysisRequest.COMPLETION,
            current,
            edit = null,
            changeClass = IncrementalChangeClass.EXPRESSION_OR_STATEMENT,
            environmentGenerationMatches = true,
            singleSource = true,
            kotlinAbiOrSyntheticInput = false,
        )
    assertEquals(IncrementalAnalysisPlanKind.FULL, noEdit.kind)
    assertEquals(IncrementalAnalysisPlan.Reason.NO_VERIFIED_EDIT, noEdit.reason)

    val changedEnvironment = plan(
        IncrementalAnalysisRequest.SIGNATURE,
        IncrementalChangeClass.WHITESPACE_OR_COMMENT,
        environmentGenerationMatches = false,
    )
    assertEquals(IncrementalAnalysisPlanKind.FULL, changedEnvironment.kind)
  }

  @Test
  fun targetIdentityMustMatchExactly() {
    val staleEdit = edit.copy(target = DocumentSnapshotIdentity(file, version = 3, revision = 3L))
    val plan =
        IncrementalAnalysisPlanner.plan(
            IncrementalAnalysisRequest.COMPLETION,
            current,
            staleEdit,
            IncrementalChangeClass.WHITESPACE_OR_COMMENT,
            environmentGenerationMatches = true,
            singleSource = true,
            kotlinAbiOrSyntheticInput = false,
        )
    assertEquals(IncrementalAnalysisPlanKind.FULL, plan.kind)
    assertEquals(IncrementalAnalysisPlan.Reason.TARGET_IDENTITY_MISMATCH, plan.reason)
    assertNotEquals(IncrementalAnalysisPlanKind.CANDIDATE, plan.kind)
  }

  private fun plan(
      request: IncrementalAnalysisRequest,
      changeClass: IncrementalChangeClass,
      environmentGenerationMatches: Boolean = true,
  ): IncrementalAnalysisPlan =
      IncrementalAnalysisPlanner.plan(
          request,
          current,
          edit,
          changeClass,
          environmentGenerationMatches,
          singleSource = true,
          kotlinAbiOrSyntheticInput = false,
      )
}