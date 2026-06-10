package com.tom.rv2ide.lsp.java.compiler

import com.tom.rv2ide.lsp.java.models.CompilationRequest
import com.tom.rv2ide.lsp.java.models.PartialReparseRequest
import java.net.URI
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PartialReparseDryRunPartialSnapshotProviderTest {

  @Test
  fun createPartialSnapshotReturnsNullByDefault() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()

    val snapshot =
        PartialReparseDryRunPartialSnapshotProvider().createPartialSnapshot(request, eligibility, report)

    assertNull(snapshot)
  }

  @Test
  fun createPartialSnapshotConsultsPlannerAndReturnsNullWhenPlanIsNotAvailable() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val planner = RecordingPlanner(PartialReparseDryRunIsolatedPlan.notAvailable("not available"))

    val snapshot =
        PartialReparseDryRunPartialSnapshotProvider(planner).createPartialSnapshot(request, eligibility, report)

    assertNull(snapshot)
    assertEquals(1, planner.calls)
    assertSame(request, planner.request)
    assertSame(eligibility, planner.eligibility)
    assertSame(report, planner.report)
  }

  @Test
  fun createPartialSnapshotStillReturnsNullWhenPlanIsReadyUntilSnapshotExecutionIsImplemented() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val planner = RecordingPlanner(PartialReparseDryRunIsolatedPlan.ready("ready"))

    val snapshot =
        PartialReparseDryRunPartialSnapshotProvider(planner).createPartialSnapshot(request, eligibility, report)

    assertNull(snapshot)
    assertEquals(1, planner.calls)
  }

  private class RecordingPlanner(private val plan: PartialReparseDryRunIsolatedPlan) :
      PartialReparseDryRunIsolatedPlanner() {
    var calls = 0
    var request: CompilationRequest? = null
    var eligibility: PartialReparseEligibility? = null
    var report: PartialReparseDryRunReport? = null

    override fun plan(
        request: CompilationRequest,
        eligibility: PartialReparseEligibility,
        attemptReport: PartialReparseDryRunReport,
    ): PartialReparseDryRunIsolatedPlan {
      calls++
      this.request = request
      this.eligibility = eligibility
      this.report = attemptReport
      return plan
    }
  }

  private class FakeSourceFile :
      SimpleJavaFileObject(URI.create("string:///A.java"), JavaFileObject.Kind.SOURCE) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = "class A {}"
    override fun getLastModified(): Long = 1L
  }
}