package com.tom.rv2ide.lsp.java.compiler

import com.tom.rv2ide.lsp.java.models.CompilationRequest
import com.tom.rv2ide.lsp.java.models.PartialReparseRequest
import java.net.URI
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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

  @Test
  fun createPartialSnapshotCanPassLiveCompilerToPlannerWithoutExecutingCopy() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val planner = RecordingPlanner(PartialReparseDryRunIsolatedPlan.notAvailable("not available"))
    val liveCompiler = FakeCompilerProvider()

    val snapshot =
        PartialReparseDryRunPartialSnapshotProvider(planner)
            .createPartialSnapshot(request, eligibility, report, liveCompiler)

    assertNull(snapshot)
    assertEquals(1, planner.calls)
    assertNotNull(planner.liveCompiler)
    assertSame(liveCompiler, planner.liveCompiler)
  }

  private class RecordingPlanner(private val plan: PartialReparseDryRunIsolatedPlan) :
      PartialReparseDryRunIsolatedPlanner() {
    var calls = 0
    var request: CompilationRequest? = null
    var eligibility: PartialReparseEligibility? = null
    var report: PartialReparseDryRunReport? = null
    var liveCompiler: CompilerProvider? = null

    override fun plan(
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