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
    assertFalse(session.isReady)
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
    val factory = RecordingSessionFactory(PartialReparseDryRunIsolatedSession.ready("session ready", true))

    val plan = PartialReparseDryRunIsolatedCompilerCopyProvider(factory).planCompilerCopy(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedPlan.State.READY, plan.state)
    assertEquals("session ready", plan.reason)
    assertTrue(plan.requiresCompilerCopy)
    assertFalse(plan.mayMutateLiveCompilerState)
    assertTrue(plan.isReady)
    assertEquals(1, factory.calls)
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

  private class FakeSourceFile :
      SimpleJavaFileObject(URI.create("string:///A.java"), JavaFileObject.Kind.SOURCE) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = "class A {}"
    override fun getLastModified(): Long = 1L
  }
}