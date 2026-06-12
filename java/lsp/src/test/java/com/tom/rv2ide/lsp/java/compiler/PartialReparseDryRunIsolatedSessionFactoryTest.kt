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

class PartialReparseDryRunIsolatedSessionFactoryTest {

  @Test
  fun defaultFactoryReturnsNotAvailableSession() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()

    val session =
        PartialReparseDryRunIsolatedSessionFactory().createSession(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedSession.State.NOT_AVAILABLE, session.state)
    assertEquals(PartialReparseDryRunIsolatedSessionFactory.DEFAULT_NOT_AVAILABLE_REASON, session.reason)
    assertTrue(session.requiresCompilerCopy)
    assertFalse(session.requiresClose)
    assertFalse(session.mayMutateLiveCompilerState)
    assertFalse(session.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(session.requiresFreshReusableCompiler)
    assertTrue(session.cachedCompileMustStartEmpty)
    assertFalse(session.isReady)
  }

  @Test
  fun defaultFactoryReturnsNotAvailableCompilerHandle() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()

    val handle =
        PartialReparseDryRunIsolatedSessionFactory().createCompilerHandle(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedCompilerHandle.State.NOT_AVAILABLE, handle.state)
    assertEquals(PartialReparseDryRunIsolatedSessionFactory.DEFAULT_NOT_AVAILABLE_REASON, handle.reason)
    assertFalse(handle.hasCompilerCopy)
    assertFalse(handle.requiresDestroy)
    assertFalse(handle.requiresClose)
    assertFalse(handle.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(handle.requiresFreshReusableCompiler)
    assertTrue(handle.cachedCompileMustStartEmpty)
    assertFalse(handle.isCreated)
  }

  @Test
  fun defaultFactoryReturnsNotAvailableSessionCandidate() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()

    val candidate =
        PartialReparseDryRunIsolatedSessionFactory().createSessionCandidate(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedSessionCandidate.State.NOT_AVAILABLE, candidate.state)
    assertEquals(PartialReparseDryRunIsolatedSessionFactory.DEFAULT_NOT_AVAILABLE_REASON, candidate.reason)
    assertFalse(candidate.hasCompilerCopyCandidate)
    assertFalse(candidate.requiresDestroy)
    assertFalse(candidate.requiresClose)
    assertFalse(candidate.canExecuteDryRun)
    assertFalse(candidate.isCreated)
  }

  @Test
  fun defaultFactoryCreatesCopyBlueprintFromCurrentJavaCompilerCopyContract() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()

    val blueprint =
        PartialReparseDryRunIsolatedSessionFactory().createCopyBlueprint(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedSessionFactory.DEFAULT_NOT_AVAILABLE_REASON, blueprint.reason)
    assertTrue(blueprint.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(blueprint.requiresFreshReusableCompiler)
    assertTrue(blueprint.cachedCompileMustStartEmpty)
    assertTrue(blueprint.requiresExplicitDestroy)
  }

  @Test
  fun defaultFactoryCreatesSessionAssemblyFromCopyBlueprintContract() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()

    val assembly =
        PartialReparseDryRunIsolatedSessionFactory().createSessionAssembly(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedSessionFactory.DEFAULT_NOT_AVAILABLE_REASON, assembly.reason)
    assertTrue(assembly.usesJavaCompilerServiceCopyMethod)
    assertTrue(assembly.reusesLiveModuleReference)
    assertTrue(assembly.reusesLiveSourceFileManager)
    assertTrue(assembly.createsFreshReusableCompiler)
    assertTrue(assembly.startsWithEmptyCachedCompile)
    assertTrue(assembly.clearsCopiedDiagnostics)
    assertTrue(assembly.clearsCopiedModificationCache)
    assertTrue(assembly.requiresExplicitDestroy)
  }

  @Test
  fun compilerCopyProviderConsultsSessionFactoryAndPropagatesNotAvailable() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val factory = RecordingSessionFactory(PartialReparseDryRunIsolatedSession.notAvailable("session missing"))

    val plan = PartialReparseDryRunIsolatedCompilerCopyProvider(factory).planCompilerCopy(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedPlan.State.NOT_AVAILABLE, plan.state)
    assertEquals("session missing", plan.reason)
    assertEquals(1, factory.calls)
    assertSame(request, factory.request)
    assertSame(eligibility, factory.eligibility)
    assertSame(report, factory.report)
  }

  @Test
  fun compilerCopyProviderReturnsReadyWhenSessionFactoryReturnsReadySession() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val factory =
        RecordingSessionFactory(
            PartialReparseDryRunIsolatedSession.ready(
                "session ready",
                true,
                true,
                true,
                true,
            )
        )

    val plan = PartialReparseDryRunIsolatedCompilerCopyProvider(factory).planCompilerCopy(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedPlan.State.READY, plan.state)
    assertEquals("session ready", plan.reason)
    assertTrue(plan.requiresCompilerCopy)
    assertFalse(plan.mayMutateLiveCompilerState)
    assertTrue(plan.isReady)
    assertEquals(1, factory.calls)
  }

  @Test
  fun createSessionCandidateReturnsCreatedWhenHandleIsCreated() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val factory = HandleBackedSessionFactory()

    val candidate = factory.createSessionCandidate(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedSessionCandidate.State.CREATED, candidate.state)
    assertEquals("handle created", candidate.reason)
    assertTrue(candidate.hasCompilerCopyCandidate)
    assertTrue(candidate.requiresDestroy)
    assertTrue(candidate.requiresClose)
    assertFalse(candidate.canExecuteDryRun)
    assertTrue(candidate.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(candidate.requiresFreshReusableCompiler)
    assertTrue(candidate.cachedCompileMustStartEmpty)
    assertTrue(candidate.isCreated)
  }

  @Test
  fun createSessionReturnsReadyWhenCandidateIsCreated() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val factory = CandidateBackedSessionFactory()

    val session = factory.createSession(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedSession.State.READY, session.state)
    assertEquals("candidate created", session.reason)
    assertTrue(session.requiresCompilerCopy)
    assertTrue(session.requiresClose)
    assertFalse(session.mayMutateLiveCompilerState)
    assertTrue(session.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(session.requiresFreshReusableCompiler)
    assertTrue(session.cachedCompileMustStartEmpty)
    assertTrue(session.isReady)
  }

  private class RecordingSessionFactory(private val session: PartialReparseDryRunIsolatedSession) :
      PartialReparseDryRunIsolatedSessionFactory() {
    var calls = 0
    var request: CompilationRequest? = null
    var eligibility: PartialReparseEligibility? = null
    var report: PartialReparseDryRunReport? = null

    override fun createSession(
        request: CompilationRequest,
        eligibility: PartialReparseEligibility,
        attemptReport: PartialReparseDryRunReport,
    ): PartialReparseDryRunIsolatedSession {
      calls++
      this.request = request
      this.eligibility = eligibility
      this.report = attemptReport
      return session
    }
  }

  private class HandleBackedSessionFactory : PartialReparseDryRunIsolatedSessionFactory() {
    override fun createCompilerHandle(
        request: CompilationRequest,
        eligibility: PartialReparseEligibility,
        attemptReport: PartialReparseDryRunReport,
    ): PartialReparseDryRunIsolatedCompilerHandle {
      return PartialReparseDryRunIsolatedCompilerHandle.created(
          "handle created",
          true,
          true,
          true,
          true,
          true,
      )
    }
  }

  private class CandidateBackedSessionFactory : PartialReparseDryRunIsolatedSessionFactory() {
    override fun createSessionCandidate(
        request: CompilationRequest,
        eligibility: PartialReparseEligibility,
        attemptReport: PartialReparseDryRunReport,
    ): PartialReparseDryRunIsolatedSessionCandidate {
      return PartialReparseDryRunIsolatedSessionCandidate.created(
          "candidate created",
          true,
          true,
          true,
          true,
          true,
      )
    }
  }

  private class FakeSourceFile :
      SimpleJavaFileObject(URI.create("string:///A.java"), JavaFileObject.Kind.SOURCE) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = "class A {}"
    override fun getLastModified(): Long = 1L
  }
}
