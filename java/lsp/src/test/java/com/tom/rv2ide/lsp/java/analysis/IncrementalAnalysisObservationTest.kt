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

import com.itsaky.androidide.treesitter.TreeSitter
import com.tom.rv2ide.lsp.java.compiler.StableCompilationInputShape
import com.tom.rv2ide.projects.models.DocumentSnapshotIdentity
import com.tom.rv2ide.projects.models.OneHopDocumentEdit
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncrementalAnalysisObservationTest {
  companion object {
    @Volatile private var nativeLoadAttempted = false
    @Volatile private var nativeLoadFailure: Throwable? = null

    @Synchronized
    private fun loadNativeLibraries(): Throwable? {
      if (!nativeLoadAttempted) {
        nativeLoadAttempted = true
        nativeLoadFailure =
            try {
              TreeSitter.loadLibrary()
              System.loadLibrary("tree-sitter-java")
              null
            } catch (error: Throwable) {
              error
            }
      }
      return nativeLoadFailure
    }
  }

  private val file: Path = Paths.get("/tmp/Observation.java")

  @Test
  fun commentWhitespaceProducesReuseStableObservation() {
    val base = "class A { void test() { // hello\n int value = 1; } }"
    val offset = base.indexOf("hello") + "hello".length
    val observation = observe(base, offset, " ", "", IncrementalAnalysisRequest.COMPLETION)

    assertEquals(IncrementalChangeClass.WHITESPACE_OR_COMMENT, observation.changeClass)
    assertEquals(IncrementalAnalysisPlanKind.REUSE_STABLE, observation.plan.kind)
    assertEquals(IncrementalAnalysisPlan.Reason.VERIFIED_TRIVIAL_EDIT, observation.plan.reason)
  }

  @Test
  fun methodBodyEditProducesCandidateObservation() {
    val base = "class A { void test() { int value = 1; } }"
    val offset = base.indexOf("value")
    val observation = observe(base, offset, "other", "value", IncrementalAnalysisRequest.SIGNATURE)

    assertEquals(IncrementalChangeClass.EXPRESSION_OR_STATEMENT, observation.changeClass)
    assertEquals(IncrementalAnalysisPlanKind.CANDIDATE, observation.plan.kind)
    assertEquals(IncrementalAnalysisPlan.Reason.VERIFIED_CANDIDATE_EDIT, observation.plan.reason)
  }

  @Test
  fun diagnosticsSkipsSyntaxClassificationAndUsesFullPlan() {
    val observation =
        IncrementalAnalysisObserver.observe(
            input(
                request = IncrementalAnalysisRequest.DIAGNOSTICS,
                targetContent = "not valid Java",
                edit = null,
            ))

    assertEquals(IncrementalChangeClass.UNKNOWN, observation.changeClass)
    assertEquals(IncrementalAnalysisPlanKind.FULL, observation.plan.kind)
    assertEquals(IncrementalAnalysisPlan.Reason.DIAGNOSTICS_REQUIRE_FULL, observation.plan.reason)
  }

  @Test
  fun staleTargetSkipsSyntaxClassificationAndUsesFullPlan() {
    val staleEdit = edit(baseStart = 0, removed = "", replacement = " ", targetVersion = 3)
    val observation =
        IncrementalAnalysisObserver.observe(
            input(
                request = IncrementalAnalysisRequest.COMPLETION,
                targetContent = "not valid Java",
                edit = staleEdit,
            ))

    assertEquals(IncrementalChangeClass.UNKNOWN, observation.changeClass)
    assertEquals(IncrementalAnalysisPlanKind.FULL, observation.plan.kind)
    assertEquals(IncrementalAnalysisPlan.Reason.TARGET_IDENTITY_MISMATCH, observation.plan.reason)
  }

  @Test
  fun missingOrNonProvenStableInputSkipsSyntaxClassificationAndUsesFullPlan() {
    val missingShape =
        IncrementalAnalysisObserver.observe(
            input(
                request = IncrementalAnalysisRequest.SIGNATURE,
                targetContent = "not valid Java",
                edit = edit(baseStart = 0, removed = "", replacement = " "),
                stableInputShape = null,
            ))
    assertEquals(IncrementalChangeClass.UNKNOWN, missingShape.changeClass)
    assertEquals(IncrementalAnalysisPlan.Reason.MULTI_SOURCE, missingShape.plan.reason)

    val kotlinStub =
        IncrementalAnalysisObserver.observe(
            input(
                request = IncrementalAnalysisRequest.SIGNATURE,
                targetContent = "not valid Java",
                edit = edit(baseStart = 0, removed = "", replacement = " "),
                stableInputShape = StableCompilationInputShape(1, 2, 1, 0),
            ))
    assertEquals(IncrementalChangeClass.UNKNOWN, kotlinStub.changeClass)
    assertEquals(IncrementalAnalysisPlan.Reason.MULTI_SOURCE, kotlinStub.plan.reason)

    val transformedInput =
        IncrementalAnalysisObserver.observe(
            input(
                request = IncrementalAnalysisRequest.SIGNATURE,
                targetContent = "not valid Java",
                edit = edit(baseStart = 0, removed = "", replacement = " "),
                stableInputMatchesTarget = false,
            ))
    assertEquals(IncrementalChangeClass.UNKNOWN, transformedInput.changeClass)
    assertEquals(IncrementalAnalysisPlan.Reason.MULTI_SOURCE, transformedInput.plan.reason)
  }

  private fun observe(
      base: String,
      offset: Int,
      replacement: String,
      removed: String,
      request: IncrementalAnalysisRequest,
  ): IncrementalAnalysisObservation {
    ensureNativeLibrariesLoaded()
    val target = base.replaceRange(offset until offset + removed.length, replacement)
    return IncrementalAnalysisObserver.observe(
        input(request, target, edit(offset, removed, replacement)))
  }

  private fun input(
      request: IncrementalAnalysisRequest,
      targetContent: String,
      edit: OneHopDocumentEdit?,
      stableInputShape: StableCompilationInputShape? =
          StableCompilationInputShape(1, 1, 0, 0),
      stableInputMatchesTarget: Boolean = true,
  ): IncrementalAnalysisObservationInput {
    return IncrementalAnalysisObservationInput(
        request = request,
        file = file,
        targetContent = targetContent,
        current = DocumentSnapshotIdentity(file, 2, 2L),
        edit = edit,
        environmentGenerationMatches = true,
        stableInputShape = stableInputShape,
        stableInputMatchesTarget = stableInputMatchesTarget,
    )
  }

  private fun edit(
      baseStart: Int,
      removed: String,
      replacement: String,
      targetVersion: Int = 2,
  ): OneHopDocumentEdit {
    return OneHopDocumentEdit(
        base = DocumentSnapshotIdentity(file, 1, 1L),
        target = DocumentSnapshotIdentity(file, targetVersion, targetVersion.toLong()),
        baseStartIndex = baseStart,
        baseEndIndex = baseStart + removed.length,
        removedText = removed,
        replacementText = replacement,
        kind = if (removed.isEmpty()) OneHopDocumentEdit.Kind.INSERT else OneHopDocumentEdit.Kind.REPLACE,
    )
  }

  private fun ensureNativeLibrariesLoaded() {
    val loadFailure = loadNativeLibraries()
    assertNull(
        "Unable to explicitly load Tree-sitter core/Java native libraries: " +
            nativeFailureDescription(loadFailure),
        loadFailure,
    )
  }

  private fun nativeFailureDescription(error: Throwable?): String {
    if (error == null) return "none"
    return error.javaClass.name + ": " + (error.message ?: error.toString())
  }
}