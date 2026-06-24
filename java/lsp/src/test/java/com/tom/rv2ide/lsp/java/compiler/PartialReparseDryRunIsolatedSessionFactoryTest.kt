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

class PartialReparseDryRunIsolatedSessionFactoryTest {

  @Test
  fun defaultFactoryReturnsNotAvailableSession() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
val session =
        PartialReparseDryRunIsolatedSessionFactory().createSession(request, eligibility, report)
    val executionAttemptResult =
        PartialReparseDryRunIsolatedSessionFactory()
            .createIsolatedExecutionAttemptResult(request, eligibility, report)

    val executablePreflightResult = executionAttemptResult.preflightResult
    val attemptExecutorBridge =
        PartialReparseDryRunIsolatedSessionFactory()
            .createAttemptExecutorBridge(request, eligibility, report)
    val observation =
        PartialReparseDryRunIsolatedSessionFactory()
            .createExecutionConsumerObservation(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedSession.State.NOT_AVAILABLE, session.state)

    assertEquals(PartialReparseDryRunIsolatedSessionFactory.DEFAULT_NOT_AVAILABLE_REASON, session.reason)
    assertTrue(session.requiresCompilerCopy)
    assertFalse(session.requiresClose)
    assertFalse(session.cleanupPlan.isRequired)
    assertFalse(session.mayMutateLiveCompilerState)
    assertFalse(session.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(session.requiresFreshReusableCompiler)
    assertTrue(session.cachedCompileMustStartEmpty)
    assertFalse(session.isReady)
    assertEquals(PartialReparseDryRunIsolatedExecutionAttemptResult.State.NOT_STARTED, executionAttemptResult.state)
    assertEquals(PartialReparseDryRunIsolatedAttemptExecutorBridge.State.NOT_BRIDGED, attemptExecutorBridge.state)
    assertEquals(PartialReparseDryRunIsolatedExecutionConsumerObservation.State.NOT_READY, observation.state)
    assertFalse(executionAttemptResult.preflightResult.session.isReady)
    assertFalse(observation.executionAttemptResult.preflightResult.session.isReady)
    assertFalse(observation.bridgeAttempted)

  }
  @Test
  fun defaultFactoryReturnsNotAvailableCompilerReference() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()

    val bindingResult =
        PartialReparseDryRunIsolatedSessionFactory().createCompilerBindingResult(request, eligibility, report)
    val reference =
        PartialReparseDryRunIsolatedSessionFactory().createCompilerReference(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedCompilerBindingResult.State.NOT_BOUND, bindingResult.state)
    assertFalse(bindingResult.bindingAttempted)
    assertEquals(PartialReparseDryRunIsolatedCompilerReference.State.NOT_AVAILABLE, reference.state)
    assertEquals(PartialReparseDryRunIsolatedSessionFactory.DEFAULT_NOT_AVAILABLE_REASON, reference.reason)
    assertFalse(reference.hasCompilerReference)
    assertFalse(reference.requiresDestroy)
    assertFalse(reference.requiresClose)
    assertFalse(reference.isCreated)
  }

  @Test
  fun defaultFactoryReturnsNotAllowedCreationGuard() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()

    val guard =
        PartialReparseDryRunIsolatedSessionFactory().createCompilerCreationGuard(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedCompilerCreationGuard.State.NOT_ALLOWED, guard.state)
    assertEquals(PartialReparseDryRunIsolatedSessionFactory.DEFAULT_NOT_AVAILABLE_REASON, guard.reason)
    assertFalse(guard.mayCreateCompilerCopy)
    assertFalse(guard.mayMutateLiveCompilerState)
    assertFalse(guard.isAllowed)
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
    assertFalse(handle.cleanupPlan.isRequired)
    assertFalse(handle.isCreated)
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
    assertFalse(candidate.cleanupPlan.isRequired)
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
  fun createCompilerCreationResultReturnsNotCreatedWhenGuardRejects() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
val result =
        PartialReparseDryRunIsolatedSessionFactory().createCompilerCreationResult(request, eligibility, report)
val slot = PartialReparseDryRunIsolatedSessionFactory().createCompilerSlot(request, eligibility, report)
    val acquisition =
        PartialReparseDryRunIsolatedSessionFactory().createCompilerAcquisition(request, eligibility, report)
    val objectAcquisitionResult =
        PartialReparseDryRunIsolatedSessionFactory()
            .createCompilerObjectAcquisitionResult(request, eligibility, report)
    val objectMaterialization =
        PartialReparseDryRunIsolatedSessionFactory()
            .createCompilerObjectMaterialization(request, eligibility, report)
    val objectFill =
        PartialReparseDryRunIsolatedSessionFactory().createCompilerObjectFill(request, eligibility, report)
    val objectAttachResult =
        PartialReparseDryRunIsolatedSessionFactory()
            .createCompilerObjectAttachResult(request, eligibility, report)
    val cleanupExecutor =
        PartialReparseDryRunIsolatedSessionFactory().createCleanupExecutor(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedCompilerCreationResult.State.NOT_CREATED, result.state)

    assertEquals(PartialReparseDryRunIsolatedSessionFactory.DEFAULT_NOT_AVAILABLE_REASON, result.reason)
    assertFalse(result.hasCreatedCompiler)
    assertFalse(result.requiresDestroy)
    assertFalse(result.requiresClose)
    assertFalse(result.cleanupRequired)
    assertFalse(result.isCreated)
    assertEquals(PartialReparseDryRunIsolatedCompilerSlot.State.NOT_ALLOCATED, slot.state)
    assertFalse(slot.hasReservedSlot)
assertEquals(PartialReparseDryRunIsolatedCompilerAcquisition.State.NOT_ACQUIRED, acquisition.state)
    assertFalse(acquisition.acquisitionAttempted)
    assertEquals(
        PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult.State.NOT_REQUESTED,
        objectAcquisitionResult.state,
    )
    assertFalse(objectAcquisitionResult.acquisitionAttempted)
    assertEquals(
        PartialReparseDryRunIsolatedCompilerObjectMaterialization.State.NOT_MATERIALIZED,
        objectMaterialization.state,
    )
    assertFalse(objectMaterialization.materializationAttempted)
    assertEquals(PartialReparseDryRunIsolatedCompilerObjectFill.State.NOT_FILLED, objectFill.state)
    assertFalse(objectFill.fillAttempted)
    assertEquals(PartialReparseDryRunIsolatedCompilerObjectAttachResult.State.NOT_ATTACHED, objectAttachResult.state)
    assertFalse(objectAttachResult.attachAttempted)
    assertFalse(objectAttachResult.reference.isCreated)
    assertEquals(PartialReparseDryRunIsolatedCleanupExecutor.State.NOT_NEEDED, cleanupExecutor.state)

    assertFalse(cleanupExecutor.isPending)
  }

  @Test
  fun createCompilerReferenceReturnsCreatedWhenCreationGuardAllows() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val factory = GuardAllowedSessionFactory()
val result = factory.createCompilerCreationResult(request, eligibility, report)
    val slot = factory.createCompilerSlot(request, eligibility, report)
val acquisition = factory.createCompilerAcquisition(request, eligibility, report)
    val objectAcquisitionResult =
        factory.createCompilerObjectAcquisitionResult(request, eligibility, report)
    val objectMaterialization =
        factory.createCompilerObjectMaterialization(request, eligibility, report)
    val objectFill = factory.createCompilerObjectFill(request, eligibility, report)
    val objectAttachResult =
        factory.createCompilerObjectAttachResult(request, eligibility, report)
    val bindingResult = factory.createCompilerBindingResult(request, eligibility, report)
    val cleanupExecutor = factory.createCleanupExecutor(request, eligibility, report)
    val reference = factory.createCompilerReference(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedCompilerCreationResult.State.CREATED, result.state)
    assertEquals("guard allowed", result.reason)
    assertFalse(result.hasCreatedCompiler)
    assertTrue(result.requiresDestroy)
    assertTrue(result.requiresClose)
    assertTrue(result.isCreated)
    assertEquals(PartialReparseDryRunIsolatedCompilerSlot.State.RESERVED, slot.state)
    assertTrue(slot.hasReservedSlot)
    assertFalse(slot.hasCompilerObject)
    assertTrue(slot.ownedBySession)
assertEquals(PartialReparseDryRunIsolatedCompilerAcquisition.State.RESERVED, acquisition.state)
    assertFalse(acquisition.acquisitionAttempted)
    assertEquals(
        PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult.State.RESERVED,
        objectAcquisitionResult.state,
    )
    assertFalse(objectAcquisitionResult.acquisitionAttempted)
    assertEquals(
        PartialReparseDryRunIsolatedCompilerObjectMaterialization.State.RESERVED,
        objectMaterialization.state,
    )
    assertFalse(objectMaterialization.materializationAttempted)
    assertEquals(PartialReparseDryRunIsolatedCompilerObjectFill.State.RESERVED, objectFill.state)
    assertFalse(objectFill.fillAttempted)
    assertEquals(PartialReparseDryRunIsolatedCompilerObjectAttachResult.State.DEFERRED, objectAttachResult.state)
    assertFalse(objectAttachResult.attachAttempted)
    assertTrue(objectAttachResult.reference.isCreated)
    assertEquals(PartialReparseDryRunIsolatedCompilerBindingResult.State.DEFERRED, bindingResult.state)

    assertFalse(bindingResult.bindingAttempted)
    assertFalse(bindingResult.hasBoundCompilerObject)
    assertTrue(reference.isCreated)
    assertEquals(PartialReparseDryRunIsolatedCleanupExecutor.State.PENDING, cleanupExecutor.state)

    assertTrue(cleanupExecutor.shouldRunOnFailure)
    assertFalse(cleanupExecutor.shouldRunOnSuccess)
    assertEquals(PartialReparseDryRunIsolatedCompilerReference.State.CREATED, reference.state)
    assertEquals("guard allowed", reference.reason)
    assertFalse(reference.hasCompilerReference)
    assertTrue(reference.requiresDestroy)
    assertTrue(reference.requiresClose)
    assertTrue(reference.isCreated)
  }

  @Test
  fun createCompilerHandleReturnsCreatedWhenReferenceIsCreated() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val factory = ReferenceBackedSessionFactory()

    val handle = factory.createCompilerHandle(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedCompilerHandle.State.CREATED, handle.state)
    assertEquals("reference created", handle.reason)
    assertTrue(handle.hasCompilerCopy)
    assertTrue(handle.requiresDestroy)
    assertTrue(handle.requiresClose)
    assertTrue(handle.cleanupPlan.isRequired)
    assertTrue(handle.cleanupPlan.cleanupOwnedBySession)
    assertTrue(handle.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(handle.requiresFreshReusableCompiler)
    assertTrue(handle.cachedCompileMustStartEmpty)
    assertTrue(handle.isCreated)
  }

  @Test
  fun createCompilerHandleWorksWhenReferenceStageIsCreatedWithoutInstantiatingCompiler() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val factory = ReferenceHoldingSessionFactory()

    val handle = factory.createCompilerHandle(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedCompilerHandle.State.CREATED, handle.state)
    assertEquals("reference with compiler", handle.reason)
    assertTrue(handle.hasCompilerCopy)
    assertTrue(handle.requiresDestroy)
    assertTrue(handle.requiresClose)
    assertTrue(handle.cleanupPlan.isRequired)
    assertTrue(handle.cleanupPlan.cleanupOwnedBySession)
    assertTrue(handle.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(handle.requiresFreshReusableCompiler)
    assertTrue(handle.cachedCompileMustStartEmpty)
    assertTrue(handle.isCreated)
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
    assertTrue(candidate.cleanupPlan.isRequired)
    assertTrue(candidate.cleanupPlan.cleanupOwnedBySession)
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
    val executionAttemptResult = factory.createIsolatedExecutionAttemptResult(request, eligibility, report)

    val executablePreflightResult = executionAttemptResult.preflightResult
    val attemptExecutorBridge = factory.createAttemptExecutorBridge(request, eligibility, report)
    val observation = factory.createExecutionConsumerObservation(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedSession.State.READY, session.state)

    assertEquals("candidate created", session.reason)
    assertEquals(PartialReparseDryRunIsolatedExecutionAttemptResult.State.DEFERRED, executionAttemptResult.state)
    assertEquals(PartialReparseDryRunIsolatedAttemptExecutorBridge.State.DEFERRED, attemptExecutorBridge.state)
    assertEquals(PartialReparseDryRunIsolatedExecutionConsumerObservation.State.DEFERRED, observation.state)
    assertTrue(executionAttemptResult.preflightResult.session.isReady)
    assertTrue(observation.executionAttemptResult.preflightResult.session.isReady)
    assertFalse(observation.bridgeAttempted)
    assertTrue(session.requiresCompilerCopy)

    assertTrue(session.requiresClose)
    assertTrue(session.cleanupPlan.isRequired)
    assertTrue(session.cleanupPlan.cleanupOwnedBySession)
    assertFalse(session.mayMutateLiveCompilerState)
    assertTrue(session.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(session.requiresFreshReusableCompiler)
    assertTrue(session.cachedCompileMustStartEmpty)
    assertTrue(session.isReady)
  }
  @Test
  fun createExecutionAttemptCanBeBridgedThroughSingleHookWithoutAddingNewLayers() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val factory = StartedAttemptSessionFactory()

    val executionAttemptResult = factory.createIsolatedExecutionAttemptResult(request, eligibility, report)

    assertEquals(PartialReparseDryRunIsolatedExecutionAttemptResult.State.STARTED, executionAttemptResult.state)
    assertTrue(executionAttemptResult.attemptStarted)
  }

  @Test
  fun defaultFactoryDoesNotAllowFutureCompilerCopyBridge() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val factory = CandidateBackedSessionFactory()
    val preflightResult = factory.createSessionExecutionPreflight(request, eligibility, report)

    assertFalse(factory.allowsFutureCompilerCopyBridge(request, eligibility, report, preflightResult))
  }

  @Test
  fun createSessionCanReceiveLiveCompilerSourceWithoutChangingDefaultSemantics() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val liveCompiler = FakeCompilerProvider()
    val factory = RecordingLiveCompilerSessionFactory(PartialReparseDryRunIsolatedSession.notAvailable("session missing"))

    val session = factory.createSession(request, eligibility, report, liveCompiler)

    assertEquals(PartialReparseDryRunIsolatedSession.State.NOT_AVAILABLE, session.state)
    assertEquals("session missing", session.reason)
    assertEquals(1, factory.calls)
    assertNotNull(factory.liveCompiler)
    assertSame(liveCompiler, factory.liveCompiler)
  }

  @Test
  fun executionAttemptBridgeCanObserveLiveCompilerSourceWithoutExecutingCopy() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())
    val report = PartialReparseDryRunReport.notCreated()
    val liveCompiler = FakeCompilerProvider()
    val factory = ObservingBridgeSessionFactory()

    val executionAttemptResult =
        factory.createIsolatedExecutionAttemptResult(request, eligibility, report, liveCompiler)

    assertEquals(PartialReparseDryRunIsolatedExecutionAttemptResult.State.DEFERRED, executionAttemptResult.state)
    assertFalse(executionAttemptResult.attemptStarted)
    assertNotNull(factory.observedLiveCompiler)
    assertSame(liveCompiler, factory.observedLiveCompiler)
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

  private class RecordingLiveCompilerSessionFactory(private val session: PartialReparseDryRunIsolatedSession) :
      PartialReparseDryRunIsolatedSessionFactory() {
    var calls = 0
    var request: CompilationRequest? = null
    var eligibility: PartialReparseEligibility? = null
    var report: PartialReparseDryRunReport? = null
    var liveCompiler: CompilerProvider? = null

    override fun createSession(
        request: CompilationRequest,
        eligibility: PartialReparseEligibility,
        attemptReport: PartialReparseDryRunReport,
        liveCompiler: CompilerProvider?,
    ): PartialReparseDryRunIsolatedSession {
      calls++
      this.request = request
      this.eligibility = eligibility
      this.report = attemptReport
      this.liveCompiler = liveCompiler
      return session
    }
  }
  private class GuardAllowedSessionFactory : PartialReparseDryRunIsolatedSessionFactory() {
    override fun createCompilerCreationGuard(
        request: CompilationRequest,
        eligibility: PartialReparseEligibility,
        attemptReport: PartialReparseDryRunReport,
    ): PartialReparseDryRunIsolatedCompilerCreationGuard {
      return PartialReparseDryRunIsolatedCompilerCreationGuard.allowed("guard allowed")
    }
  }

  private class ReferenceBackedSessionFactory : PartialReparseDryRunIsolatedSessionFactory() {
    override fun createCompilerReference(
        request: CompilationRequest,
        eligibility: PartialReparseEligibility,
        attemptReport: PartialReparseDryRunReport,
    ): PartialReparseDryRunIsolatedCompilerReference {
      return PartialReparseDryRunIsolatedCompilerReference.createdWithoutReference(
          "reference created",
          true,
          true,
      )
    }
  }

  private class ReferenceHoldingSessionFactory : PartialReparseDryRunIsolatedSessionFactory() {
    override fun createCompilerReference(
        request: CompilationRequest,
        eligibility: PartialReparseEligibility,
        attemptReport: PartialReparseDryRunReport,
    ): PartialReparseDryRunIsolatedCompilerReference {
      return PartialReparseDryRunIsolatedCompilerReference.createdWithoutReference(
          "reference with compiler",
          true,
          true,
      )
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

  private open class CandidateBackedSessionFactory : PartialReparseDryRunIsolatedSessionFactory() {
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
private class StartedAttemptSessionFactory : CandidateBackedSessionFactory() {
  override fun allowsFutureCompilerCopyBridge(
      request: CompilationRequest,
      eligibility: PartialReparseEligibility,
      attemptReport: PartialReparseDryRunReport,
      preflightResult: PartialReparseDryRunIsolatedSessionExecutionPreflight,
  ): Boolean = true

  override fun bridgeExecutionAttempt(
      request: CompilationRequest,
      eligibility: PartialReparseEligibility,
      attemptReport: PartialReparseDryRunReport,
      preflightResult: PartialReparseDryRunIsolatedSessionExecutionPreflight,
  ): PartialReparseDryRunIsolatedExecutionAttemptResult {
    return PartialReparseDryRunIsolatedExecutionAttemptResult.started(
        "attempt started",
        preflightResult,
        true,
    )
  }
}

private class ObservingBridgeSessionFactory : CandidateBackedSessionFactory() {
  var observedLiveCompiler: CompilerProvider? = null

  override fun createSessionCandidate(
      request: CompilationRequest,
      eligibility: PartialReparseEligibility,
      attemptReport: PartialReparseDryRunReport,
      liveCompiler: CompilerProvider?,
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

  override fun allowsFutureCompilerCopyBridge(
      request: CompilationRequest,
      eligibility: PartialReparseEligibility,
      attemptReport: PartialReparseDryRunReport,
      preflightResult: PartialReparseDryRunIsolatedSessionExecutionPreflight,
      liveCompiler: CompilerProvider?,
  ): Boolean {
    observedLiveCompiler = liveCompiler
    return false
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
