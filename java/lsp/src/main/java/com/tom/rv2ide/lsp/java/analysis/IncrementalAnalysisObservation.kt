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

import com.tom.rv2ide.lsp.java.compiler.StableCompilationInputShape
import com.tom.rv2ide.projects.models.DocumentSnapshotIdentity
import com.tom.rv2ide.projects.models.OneHopDocumentEdit
import java.nio.file.Path

/** Immutable inputs for one observation-only incremental-analysis decision. */
data class IncrementalAnalysisObservationInput(
    val request: IncrementalAnalysisRequest,
    val file: Path,
    val targetContent: String,
    val current: DocumentSnapshotIdentity,
    val edit: OneHopDocumentEdit?,
    val environmentGenerationMatches: Boolean,
    /** Shape captured from the same stable CompileTask, never a service-global later lookup. */
    val stableInputShape: StableCompilationInputShape?,
    /** Caller proved the stable javac source text is the same target content used below. */
    val stableInputMatchesTarget: Boolean,
)

/** Syntax classification and plan selected for an observation. This is not an execution result. */
data class IncrementalAnalysisObservation(
    val changeClass: IncrementalChangeClass,
    val plan: IncrementalAnalysisPlan,
)

/**
 * Connects a verified one-hop edit to the syntax classifier and pure planner without creating or
 * mutating compiler state. Preconditions that already require FULL skip native parsing entirely.
 */
object IncrementalAnalysisObserver {
  fun observe(input: IncrementalAnalysisObservationInput): IncrementalAnalysisObservation {
    val shape = input.stableInputShape
    val singleSource =
        input.stableInputMatchesTarget &&
            shape != null &&
            shape.isProvenSingleJavaSourceWithoutKotlinStubs
    val kotlinAbiOrSyntheticInput =
        shape == null || shape.kotlinAbiStubCount > 0 || shape.additionalJavaSourceCount > 0
    val preliminaryPlan =
        IncrementalAnalysisPlanner.plan(
            input.request,
            input.current,
            input.edit,
            IncrementalChangeClass.UNKNOWN,
            input.environmentGenerationMatches,
            singleSource,
            kotlinAbiOrSyntheticInput,
        )
    if (preliminaryPlan.reason != IncrementalAnalysisPlan.Reason.UNKNOWN_CHANGE) {
      return IncrementalAnalysisObservation(IncrementalChangeClass.UNKNOWN, preliminaryPlan)
    }

    val verifiedEdit = requireNotNull(input.edit)
    val changeClass =
        TreeSitterIncrementalChangeClassifier.classify(input.file, input.targetContent, verifiedEdit)
    val plan =
        IncrementalAnalysisPlanner.plan(
            input.request,
            input.current,
            verifiedEdit,
            changeClass,
            input.environmentGenerationMatches,
            singleSource,
            kotlinAbiOrSyntheticInput,
        )
    return IncrementalAnalysisObservation(changeClass, plan)
  }
}
