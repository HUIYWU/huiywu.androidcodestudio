package com.tom.rv2ide.lsp.java.compiler

import com.tom.rv2ide.lsp.java.models.CompilationRequest
import com.tom.rv2ide.lsp.java.models.PartialReparseRequest
import java.net.URI
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCompilerCopyProviderTest {

  @Test
  fun defaultCompilerCopyProviderIsNotAvailable() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()

    val plan =
        PartialReparseDryRunIsolatedCompilerCopyProvider()
            .planCompilerCopy(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedPlan.State.NOT_AVAILABLE, plan.state)
    assertEquals(PartialReparseDryRunIsolatedCompilerCopyProvider.DEFAULT_NOT_AVAILABLE_REASON, plan.reason)
    assertTrue(plan.requiresCompilerCopy)
    assertFalse(plan.mayMutateLiveCompilerState)
    assertFalse(plan.isReady)
  }

  @Test
  fun plannerConsultsCompilerCopyProviderAndPropagatesNotAvailablePlan() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val provider = RecordingCompilerCopyProvider(PartialReparseDryRunIsolatedPlan.notAvailable("copy missing"))

    val plan = PartialReparseDryRunIsolatedPlanner(provider).plan(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedPlan.State.NOT_AVAILABLE, plan.state)
    assertEquals("copy missing", plan.reason)
    assertEquals(1, provider.calls)
    assertSame(request, provider.request)
    assertSame(eligibility, provider.eligibility)
    assertSame(report, provider.report)
  }

  @Test
  fun plannerReturnsReadyOnlyWhenCompilerCopyProviderIsReady() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val provider = RecordingCompilerCopyProvider(PartialReparseDryRunIsolatedPlan.ready("copy ready"))

    val plan = PartialReparseDryRunIsolatedPlanner(provider).plan(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedPlan.State.READY, plan.state)
    assertEquals("copy ready", plan.reason)
    assertTrue(plan.requiresCompilerCopy)
    assertFalse(plan.mayMutateLiveCompilerState)
    assertTrue(plan.isReady)
    assertEquals(1, provider.calls)
  }

  private class RecordingCompilerCopyProvider(private val plan: PartialReparseDryRunIsolatedPlan) :
      PartialReparseDryRunIsolatedCompilerCopyProvider() {
    var calls = 0
    var request: CompilationRequest? = null
    var eligibility: PartialReparseEligibility? = null
    var report: PartialReparseDryRunReport? = null

    override fun planCompilerCopy(
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
