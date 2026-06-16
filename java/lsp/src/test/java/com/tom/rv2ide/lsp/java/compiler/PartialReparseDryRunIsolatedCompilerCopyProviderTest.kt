package com.tom.rv2ide.lsp.java.compiler

import com.tom.rv2ide.lsp.java.models.CompilationRequest
import com.tom.rv2ide.lsp.java.models.PartialReparseRequest
import java.net.URI
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    assertEquals(PartialReparseDryRunIsolatedSessionFactory.DEFAULT_NOT_AVAILABLE_REASON, plan.reason)
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

  @Test
  fun plannerCanPassLiveCompilerToCompilerCopyProviderWithoutChangingPlanSemantics() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val provider = RecordingCompilerCopyProvider(PartialReparseDryRunIsolatedPlan.notAvailable("copy missing"))
    val liveCompiler = FakeCompilerProvider()

    val plan = PartialReparseDryRunIsolatedPlanner(provider).plan(request, eligibility, report, liveCompiler)

    assertEquals(PartialReparseDryRunIsolatedPlan.State.NOT_AVAILABLE, plan.state)
    assertEquals("copy missing", plan.reason)
    assertEquals(1, provider.calls)
    assertNotNull(provider.liveCompiler)
    assertSame(liveCompiler, provider.liveCompiler)
  }

  private class RecordingCompilerCopyProvider(private val plan: PartialReparseDryRunIsolatedPlan) :
      PartialReparseDryRunIsolatedCompilerCopyProvider() {
    var calls = 0
    var request: CompilationRequest? = null
    var eligibility: PartialReparseEligibility? = null
    var report: PartialReparseDryRunReport? = null
    var liveCompiler: CompilerProvider? = null

    override fun planCompilerCopy(
        request: CompilationRequest,
        eligibility: PartialReparseEligibility,
        attemptReport: PartialReparseDryRunReport,
        liveCompiler: CompilerProvider?,
    ): PartialReparseDryRunIsolatedPlan {
      calls++
      this.request = request
      this.eligibility = eligibility
      this.report = attemptReport
      this.liveCompiler = liveCompiler
      return plan
    }
  }

  private class FakeCompilerProvider : CompilerProvider {
    override fun publicTopLevelTypes() = java.util.TreeSet<String>()
    override fun packagePrivateTopLevelTypes(packageName: String) = java.util.TreeSet<String>()
    override fun findAnywhere(className: String) = java.util.Optional.empty<JavaFileObject>()
    override fun findTypeDeclaration(className: String) = CompilerProvider.NOT_FOUND
    override fun findTypeReferences(className: String) = emptyArray<java.nio.file.Path>()
    override fun findMemberReferences(className: String, memberName: String) = emptyArray<java.nio.file.Path>()
    override fun findQualifiedNames(simpleName: String, onlyOne: Boolean) = emptyList<String>()
    override fun parse(file: java.nio.file.Path): com.tom.rv2ide.lsp.java.parser.ParseTask {
      throw UnsupportedOperationException()
    }
    override fun parse(file: JavaFileObject): com.tom.rv2ide.lsp.java.parser.ParseTask {
      throw UnsupportedOperationException()
    }
    override fun compile(request: CompilationRequest): SynchronizedTask {
      throw UnsupportedOperationException()
    }
  }

  private class FakeSourceFile :
      SimpleJavaFileObject(URI.create("string:///A.java"), JavaFileObject.Kind.SOURCE) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = "class A {}"
    override fun getLastModified(): Long = 1L
  }
}
