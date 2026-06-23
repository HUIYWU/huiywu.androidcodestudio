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

package com.tom.rv2ide.lsp.java.compiler;

import androidx.annotation.NonNull;
import com.tom.rv2ide.lsp.java.models.CompilationRequest;

/**
 * Creates isolated dry-run session descriptors.
 *
 * <p>The default implementation stays conservative: even though {@link JavaCompilerService#copy()}
 * exists, we still need an explicit lifecycle contract before any copied compiler can participate in
 * dry-run partial snapshot generation.
 */
public class PartialReparseDryRunIsolatedSessionFactory {

  public static final String DEFAULT_NOT_AVAILABLE_REASON =
      "isolated dry-run session factory is not implemented yet";

  @NonNull
  public PartialReparseDryRunIsolatedSession createSession(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedSessionCandidate candidate =
        createSessionCandidate(request, eligibility, attemptReport);
    if (!candidate.isCreated()) {
      return PartialReparseDryRunIsolatedSession.notAvailable(candidate.reason);
    }
    return PartialReparseDryRunIsolatedSession.ready(
        candidate.cleanupPlan.reason,
        candidate.requiresClose,
        candidate.sharesSourceFileManagerWithLiveCompiler,
        candidate.requiresFreshReusableCompiler,
        candidate.cachedCompileMustStartEmpty);
  }

  @NonNull
  public PartialReparseDryRunIsolatedSession createSession(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedSessionCandidate candidate =
        createSessionCandidate(request, eligibility, attemptReport, liveCompiler);
    if (!candidate.isCreated()) {
      return PartialReparseDryRunIsolatedSession.notAvailable(candidate.reason);
    }
    return PartialReparseDryRunIsolatedSession.ready(
        candidate.cleanupPlan.reason,
        candidate.requiresClose,
        candidate.sharesSourceFileManagerWithLiveCompiler,
        candidate.requiresFreshReusableCompiler,
        candidate.cachedCompileMustStartEmpty);
  }
  @NonNull
  PartialReparseDryRunIsolatedSessionReadinessResult createIsolatedSessionReadinessResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedSession session =
        createSession(request, eligibility, attemptReport);
    if (!session.isReady()) {
      return PartialReparseDryRunIsolatedSessionReadinessResult.notReady(session.reason);
    }
    return PartialReparseDryRunIsolatedSessionReadinessResult.deferred(session.reason, session);
  }

  @NonNull
  PartialReparseDryRunIsolatedSessionReadinessResult createIsolatedSessionReadinessResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedSession session =
        createSession(request, eligibility, attemptReport, liveCompiler);
    if (!session.isReady()) {
      return PartialReparseDryRunIsolatedSessionReadinessResult.notReady(session.reason);
    }
    return PartialReparseDryRunIsolatedSessionReadinessResult.deferred(session.reason, session);
  }
  @NonNull
  PartialReparseDryRunIsolatedExecutablePreflightResult createExecutablePreflightResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedSessionReadinessResult sessionReadinessResult =
        createIsolatedSessionReadinessResult(request, eligibility, attemptReport);
    if (!sessionReadinessResult.session.isReady()) {
      return PartialReparseDryRunIsolatedExecutablePreflightResult.notReady(
          sessionReadinessResult.reason);
    }
    return PartialReparseDryRunIsolatedExecutablePreflightResult.deferred(
        sessionReadinessResult.reason, sessionReadinessResult);
  }

  @NonNull
  PartialReparseDryRunIsolatedSessionExecutionPreflight createSessionExecutionPreflight(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedSession session =
        createSession(request, eligibility, attemptReport);
    if (!session.isReady()) {
      return PartialReparseDryRunIsolatedSessionExecutionPreflight.notReady(session.reason);
    }
    return PartialReparseDryRunIsolatedSessionExecutionPreflight.deferred(session.reason, session);
  }

  @NonNull
  PartialReparseDryRunIsolatedExecutablePreflightResult createExecutablePreflightResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedSessionReadinessResult sessionReadinessResult =
        createIsolatedSessionReadinessResult(request, eligibility, attemptReport, liveCompiler);
    if (!sessionReadinessResult.session.isReady()) {
      return PartialReparseDryRunIsolatedExecutablePreflightResult.notReady(
          sessionReadinessResult.reason);
    }
    return PartialReparseDryRunIsolatedExecutablePreflightResult.deferred(
        sessionReadinessResult.reason, sessionReadinessResult);
  }

  @NonNull
  PartialReparseDryRunIsolatedSessionExecutionPreflight createSessionExecutionPreflight(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedSession session =
        createSession(request, eligibility, attemptReport, liveCompiler);
    if (!session.isReady()) {
      return PartialReparseDryRunIsolatedSessionExecutionPreflight.notReady(session.reason);
    }
    return PartialReparseDryRunIsolatedSessionExecutionPreflight.deferred(session.reason, session);
  }

  @NonNull
  PartialReparseDryRunIsolatedExecutionAttemptResult createIsolatedExecutionAttemptResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedSessionExecutionPreflight preflightResult =
        createSessionExecutionPreflight(request, eligibility, attemptReport);
    if (!preflightResult.session.isReady()) {
      return PartialReparseDryRunIsolatedExecutionAttemptResult.notStarted(preflightResult.reason);
    }
    return bridgeExecutionAttempt(request, eligibility, attemptReport, preflightResult);
  }

  @NonNull
  PartialReparseDryRunIsolatedExecutionAttemptResult createIsolatedExecutionAttemptResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedSessionExecutionPreflight preflightResult =
        createSessionExecutionPreflight(request, eligibility, attemptReport, liveCompiler);
    if (!preflightResult.session.isReady()) {
      return PartialReparseDryRunIsolatedExecutionAttemptResult.notStarted(preflightResult.reason);
    }
    return bridgeExecutionAttempt(request, eligibility, attemptReport, preflightResult, liveCompiler);
  }
  @NonNull
  PartialReparseDryRunIsolatedExecutionAttemptResult bridgeExecutionAttempt(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      @NonNull PartialReparseDryRunIsolatedSessionExecutionPreflight preflightResult) {
    return bridgeExecutionAttempt(request, eligibility, attemptReport, preflightResult, null);
  }

  @NonNull
  PartialReparseDryRunIsolatedExecutionAttemptResult bridgeExecutionAttempt(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      @NonNull PartialReparseDryRunIsolatedSessionExecutionPreflight preflightResult,
      CompilerProvider liveCompiler) {
    if (!allowsFutureCompilerCopyBridge(request, eligibility, attemptReport, preflightResult, liveCompiler)) {
      return PartialReparseDryRunIsolatedExecutionAttemptResult.deferred(
          preflightResult.reason, preflightResult);
    }
    return PartialReparseDryRunIsolatedExecutionAttemptResult.deferred(
        preflightResult.reason, preflightResult);
  }

  boolean allowsFutureCompilerCopyBridge(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      @NonNull PartialReparseDryRunIsolatedSessionExecutionPreflight preflightResult) {
    return allowsFutureCompilerCopyBridge(request, eligibility, attemptReport, preflightResult, null);
  }

  boolean allowsFutureCompilerCopyBridge(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      @NonNull PartialReparseDryRunIsolatedSessionExecutionPreflight preflightResult,
      CompilerProvider liveCompiler) {
    return false;
  }



  @NonNull
  PartialReparseDryRunIsolatedAttemptExecutorBridge createAttemptExecutorBridge(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedExecutionAttemptResult executionAttemptResult =
        createIsolatedExecutionAttemptResult(request, eligibility, attemptReport);
    if (!executionAttemptResult.attemptStarted && !executionAttemptResult.preflightResult.session.isReady()) {
      return PartialReparseDryRunIsolatedAttemptExecutorBridge.notBridged(
          executionAttemptResult.reason);
    }
    return PartialReparseDryRunIsolatedAttemptExecutorBridge.deferred(
        executionAttemptResult.reason, executionAttemptResult);
  }

  @NonNull
  PartialReparseDryRunIsolatedAttemptExecutorBridge createAttemptExecutorBridge(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedExecutionAttemptResult executionAttemptResult =
        createIsolatedExecutionAttemptResult(request, eligibility, attemptReport, liveCompiler);
    if (!executionAttemptResult.attemptStarted && !executionAttemptResult.preflightResult.session.isReady()) {
      return PartialReparseDryRunIsolatedAttemptExecutorBridge.notBridged(
          executionAttemptResult.reason);
    }
    return PartialReparseDryRunIsolatedAttemptExecutorBridge.deferred(
        executionAttemptResult.reason, executionAttemptResult);
  }

  @NonNull
  PartialReparseDryRunIsolatedAttemptExecutorConsumerResult createAttemptExecutorConsumerResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedExecutionConsumerObservation observation =
        createExecutionConsumerObservation(request, eligibility, attemptReport);
    return PartialReparseDryRunIsolatedAttemptExecutorConsumerResult.fromObservation(
        observation);
  }

  @NonNull
  PartialReparseDryRunIsolatedExecutionConsumerObservation createExecutionConsumerObservation(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedExecutionAttemptResult executionAttemptResult =
        createIsolatedExecutionAttemptResult(request, eligibility, attemptReport);
    if (!executionAttemptResult.preflightResult.session.isReady()) {
      return PartialReparseDryRunIsolatedExecutionConsumerObservation.notReady(
          executionAttemptResult.reason);
    }
    return PartialReparseDryRunIsolatedExecutionConsumerObservation.deferred(
        executionAttemptResult.reason,
        executionAttemptResult,
        executionAttemptResult.attemptStarted,
        executionAttemptResult.attemptFailed,
        false,
        false,
        true);
  }

  @NonNull
  PartialReparseDryRunIsolatedAttemptExecutorConsumerResult createAttemptExecutorConsumerResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedExecutionConsumerObservation observation =
        createExecutionConsumerObservation(request, eligibility, attemptReport, liveCompiler);
    return PartialReparseDryRunIsolatedAttemptExecutorConsumerResult.fromObservation(
        observation);
  }

  @NonNull
  PartialReparseDryRunIsolatedExecutionConsumerObservation createExecutionConsumerObservation(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedExecutionAttemptResult executionAttemptResult =
        createIsolatedExecutionAttemptResult(request, eligibility, attemptReport, liveCompiler);
    if (!executionAttemptResult.preflightResult.session.isReady()) {
      return PartialReparseDryRunIsolatedExecutionConsumerObservation.notReady(
          executionAttemptResult.reason);
    }
    return PartialReparseDryRunIsolatedExecutionConsumerObservation.deferred(
        executionAttemptResult.reason,
        executionAttemptResult,
        executionAttemptResult.attemptStarted,
        executionAttemptResult.attemptFailed,
        false,
        false,
        true);
  }


  @NonNull
  PartialReparseDryRunIsolatedSessionCandidateReadyResult createSessionCandidateReadyResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedSessionCandidate candidate =
        createSessionCandidate(request, eligibility, attemptReport);
    if (!candidate.isCreated()) {
      return PartialReparseDryRunIsolatedSessionCandidateReadyResult.notReady(candidate.reason);
    }
    return PartialReparseDryRunIsolatedSessionCandidateReadyResult.deferred(
        candidate.reason, candidate);
  }

  @NonNull
  PartialReparseDryRunIsolatedSessionCandidate createSessionCandidate(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedCompilerHandleReadyResult handleReadyResult =
        createCompilerHandleReadyResult(request, eligibility, attemptReport);
    if (!handleReadyResult.handle.isCreated()) {
      return PartialReparseDryRunIsolatedSessionCandidate.notAvailable(handleReadyResult.reason);
    }
    return PartialReparseDryRunIsolatedSessionCandidate.created(
        handleReadyResult.handle.cleanupPlan.reason,
        handleReadyResult.handle.cleanupPlan.requiresDestroy,
        handleReadyResult.handle.cleanupPlan.requiresClose,
        handleReadyResult.handle.sharesSourceFileManagerWithLiveCompiler,
        handleReadyResult.handle.requiresFreshReusableCompiler,
        handleReadyResult.handle.cachedCompileMustStartEmpty);
  }

  @NonNull
  PartialReparseDryRunIsolatedSessionCandidate createSessionCandidate(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedCompilerHandleReadyResult handleReadyResult =
        createCompilerHandleReadyResult(request, eligibility, attemptReport, liveCompiler);
    if (!handleReadyResult.handle.isCreated()) {
      return PartialReparseDryRunIsolatedSessionCandidate.notAvailable(handleReadyResult.reason);
    }
    return PartialReparseDryRunIsolatedSessionCandidate.created(
        handleReadyResult.handle.cleanupPlan.reason,
        handleReadyResult.handle.cleanupPlan.requiresDestroy,
        handleReadyResult.handle.cleanupPlan.requiresClose,
        handleReadyResult.handle.sharesSourceFileManagerWithLiveCompiler,
        handleReadyResult.handle.requiresFreshReusableCompiler,
        handleReadyResult.handle.cachedCompileMustStartEmpty);
  }
@NonNull
  PartialReparseDryRunIsolatedCompilerHandleReadyResult createCompilerHandleReadyResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedCompilerHandle handle =
        createCompilerHandle(request, eligibility, attemptReport);
    if (!handle.isCreated()) {
      return PartialReparseDryRunIsolatedCompilerHandleReadyResult.notReady(handle.reason);
    }
    return PartialReparseDryRunIsolatedCompilerHandleReadyResult.deferred(handle.reason, handle);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerHandleReadyResult createCompilerHandleReadyResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedCompilerHandle handle =
        createCompilerHandle(request, eligibility, attemptReport, liveCompiler);
    if (!handle.isCreated()) {
      return PartialReparseDryRunIsolatedCompilerHandleReadyResult.notReady(handle.reason);
    }
    return PartialReparseDryRunIsolatedCompilerHandleReadyResult.deferred(handle.reason, handle);
  }
@NonNull
  PartialReparseDryRunIsolatedCompilerHandle createCompilerHandle(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedCompilerReference reference =
        createCompilerReference(request, eligibility, attemptReport);
    if (!reference.isCreated()) {
      return PartialReparseDryRunIsolatedCompilerHandle.notAvailable(reference.reason);
    }
    return PartialReparseDryRunIsolatedCompilerHandle.created(
        reference.reason,
        reference.requiresDestroy,
        reference.requiresClose,
        true,
        true,
        true);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerHandle createCompilerHandle(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedCompilerReference reference =
        createCompilerReference(request, eligibility, attemptReport, liveCompiler);
    if (!reference.isCreated()) {
      return PartialReparseDryRunIsolatedCompilerHandle.notAvailable(reference.reason);
    }
    return PartialReparseDryRunIsolatedCompilerHandle.created(
        reference.reason,
        reference.requiresDestroy,
        reference.requiresClose,
        true,
        true,
        true);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerReference createCompilerReference(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedCompilerReferenceReadyResult readyResult =
        createCompilerReferenceReadyResult(request, eligibility, attemptReport);
    return readyResult.reference;
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerReference createCompilerReference(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedCompilerReferenceReadyResult readyResult =
        createCompilerReferenceReadyResult(request, eligibility, attemptReport, liveCompiler);
    return readyResult.reference;
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerReferenceReadyResult createCompilerReferenceReadyResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    return createCompilerReferenceReadyResult(request, eligibility, attemptReport, null);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerReferenceReadyResult createCompilerReferenceReadyResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedCompilerBindingResult bindingResult =
        createCompilerBindingResult(request, eligibility, attemptReport, liveCompiler);
    if (!bindingResult.reference.isCreated()) {
      return PartialReparseDryRunIsolatedCompilerReferenceReadyResult.notReady(bindingResult.reason);
    }
    if (bindingResult.isBound() && bindingResult.reference.hasCompilerReference) {
      return PartialReparseDryRunIsolatedCompilerReferenceReadyResult.ready(
          bindingResult.reason, bindingResult, bindingResult.reference);
    }
    return PartialReparseDryRunIsolatedCompilerReferenceReadyResult.deferred(
        bindingResult.reason, bindingResult, bindingResult.reference);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerBindingResult createCompilerBindingResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    return createCompilerBindingResult(request, eligibility, attemptReport, null);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerBindingResult createCompilerBindingResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedCompilerObjectAttachResult attachResult =
        createCompilerObjectAttachResult(request, eligibility, attemptReport, liveCompiler);
    if (!attachResult.reference.isCreated()) {
      return PartialReparseDryRunIsolatedCompilerBindingResult.notBound(attachResult.reason);
    }
    if (attachResult.isAttached() && attachResult.reference.hasCompilerReference) {
      return PartialReparseDryRunIsolatedCompilerBindingResult.bound(
          attachResult.reason,
          createCompilerAcquisition(request, eligibility, attemptReport, liveCompiler),
          attachResult.reference);
    }
    return PartialReparseDryRunIsolatedCompilerBindingResult.deferred(
        attachResult.reason,
        createCompilerAcquisition(request, eligibility, attemptReport, liveCompiler),
        attachResult.reference);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerObjectAttachResult createCompilerObjectAttachResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    return createCompilerObjectAttachResult(request, eligibility, attemptReport, null);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerObjectAttachResult createCompilerObjectAttachResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedCompilerObjectFill objectFill =
        createCompilerObjectFill(request, eligibility, attemptReport, liveCompiler);
    if (!(objectFill.isReserved() || objectFill.isFilled())) {
      return PartialReparseDryRunIsolatedCompilerObjectAttachResult.notAttached(objectFill.reason);
    }
    if (objectFill.isFilled() && objectFill.compiler != null) {
      return PartialReparseDryRunIsolatedCompilerObjectAttachResult.attached(
          objectFill.reason,
          objectFill,
          PartialReparseDryRunIsolatedCompilerReference.created(
              objectFill.reason,
              objectFill.compiler,
              true,
              true));
    }
    return PartialReparseDryRunIsolatedCompilerObjectAttachResult.deferred(
        objectFill.reason, objectFill);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerObjectFill createCompilerObjectFill(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    return createCompilerObjectFill(request, eligibility, attemptReport, null);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerObjectFill createCompilerObjectFill(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedCompilerObjectMaterialization objectMaterialization =
        createCompilerObjectMaterialization(request, eligibility, attemptReport, liveCompiler);
    if (!(objectMaterialization.isReserved() || objectMaterialization.isMaterialized())) {
      return PartialReparseDryRunIsolatedCompilerObjectFill.notFilled(objectMaterialization.reason);
    }
    if (objectMaterialization.isMaterialized() && objectMaterialization.compiler != null) {
      return PartialReparseDryRunIsolatedCompilerObjectFill.filled(
          objectMaterialization.reason,
          objectMaterialization.objectAcquisitionResult.acquisition.compilerSlot,
          objectMaterialization.compiler);
    }
    return PartialReparseDryRunIsolatedCompilerObjectFill.reserved(
        objectMaterialization.reason,
        objectMaterialization.objectAcquisitionResult.acquisition.compilerSlot);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerObjectMaterialization createCompilerObjectMaterialization(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    return createCompilerObjectMaterialization(request, eligibility, attemptReport, null);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerObjectMaterialization createCompilerObjectMaterialization(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult objectAcquisitionResult =
        createCompilerObjectAcquisitionResult(request, eligibility, attemptReport, liveCompiler);
    if (!(objectAcquisitionResult.isReserved() || objectAcquisitionResult.isAcquired())) {
      return PartialReparseDryRunIsolatedCompilerObjectMaterialization.notMaterialized(
          objectAcquisitionResult.reason);
    }
    if (objectAcquisitionResult.isAcquired() && objectAcquisitionResult.compiler != null) {
      return PartialReparseDryRunIsolatedCompilerObjectMaterialization.materialized(
          objectAcquisitionResult.reason, objectAcquisitionResult, objectAcquisitionResult.compiler);
    }
    return PartialReparseDryRunIsolatedCompilerObjectMaterialization.reserved(
        objectAcquisitionResult.reason, objectAcquisitionResult);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult createCompilerObjectAcquisitionResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    return createCompilerObjectAcquisitionResult(request, eligibility, attemptReport, null);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult createCompilerObjectAcquisitionResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedCompilerAcquisition acquisition =
        createCompilerAcquisition(request, eligibility, attemptReport, liveCompiler);
    if (!(acquisition.isReserved() || acquisition.isAcquired())) {
      return PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult.notRequested(
          acquisition.reason);
    }
    if (acquisition.isAcquired() && acquisition.compilerSlot.compiler != null) {
      return PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult.acquired(
          acquisition.reason, acquisition, acquisition.compilerSlot.compiler);
    }
    return PartialReparseDryRunIsolatedCompilerObjectAcquisitionResult.reserved(
        acquisition.reason, acquisition);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerAcquisition createCompilerAcquisition(

      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    return createCompilerAcquisition(request, eligibility, attemptReport, null);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerAcquisition createCompilerAcquisition(

      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedCompilerSlot compilerSlot =
        createCompilerSlot(request, eligibility, attemptReport, liveCompiler);
    if (!compilerSlot.hasReservedSlot) {
      return PartialReparseDryRunIsolatedCompilerAcquisition.notAcquired(compilerSlot.reason);
    }
    final PartialReparseDryRunIsolatedCleanupExecutor cleanupExecutor =
        createCleanupExecutor(request, eligibility, attemptReport, liveCompiler);
    if (compilerSlot.hasCompilerObject) {
      return PartialReparseDryRunIsolatedCompilerAcquisition.acquired(
          compilerSlot.reason, compilerSlot, cleanupExecutor);
    }
    return PartialReparseDryRunIsolatedCompilerAcquisition.reserved(
        compilerSlot.reason, compilerSlot, cleanupExecutor);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerSlot createCompilerSlot(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    return createCompilerSlot(request, eligibility, attemptReport, null);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerSlot createCompilerSlot(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedCompilerCreationResult creationResult =
        createCompilerCreationResult(request, eligibility, attemptReport);
    if (!creationResult.isCreated()) {
      return PartialReparseDryRunIsolatedCompilerSlot.notAllocated(creationResult.reason);
    }
    if (creationResult.hasCreatedCompiler && creationResult.compiler != null) {
      return PartialReparseDryRunIsolatedCompilerSlot.filled(
          creationResult.reason, creationResult.compiler, true);
    }
    return PartialReparseDryRunIsolatedCompilerSlot.reserved(creationResult.reason, true);
  }

  @NonNull
  PartialReparseDryRunIsolatedCleanupExecutor createCleanupExecutor(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    return createCleanupExecutor(request, eligibility, attemptReport, null);
  }

  @NonNull
  PartialReparseDryRunIsolatedCleanupExecutor createCleanupExecutor(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport,
      CompilerProvider liveCompiler) {
    final PartialReparseDryRunIsolatedCompilerSlot compilerSlot =
        createCompilerSlot(request, eligibility, attemptReport, liveCompiler);
    if (!compilerSlot.hasReservedSlot) {
      return PartialReparseDryRunIsolatedCleanupExecutor.notNeeded(compilerSlot.reason);
    }
    return PartialReparseDryRunIsolatedCleanupExecutor.pending(
        compilerSlot.reason,
        PartialReparseDryRunIsolatedCleanupPlan.required(compilerSlot.reason, true, true, true, true),
        compilerSlot,
        true,
        false);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerCreationResult createCompilerCreationResult(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedCompilerCreationGuard guard =
        createCompilerCreationGuard(request, eligibility, attemptReport);
    if (!guard.isAllowed()) {
      return PartialReparseDryRunIsolatedCompilerCreationResult.notCreated(guard.reason);
    }
    return PartialReparseDryRunIsolatedCompilerCreationResult.createdWithoutCompiler(
        guard.reason, true, true);
  }

  @NonNull
  PartialReparseDryRunIsolatedCompilerCreationGuard createCompilerCreationGuard(

      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedSessionAssembly assembly =
        createSessionAssembly(request, eligibility, attemptReport);
    return PartialReparseDryRunIsolatedCompilerCreationGuard.notAllowed(assembly.reason);
  }


  @NonNull
  PartialReparseDryRunIsolatedCopyBlueprint createCopyBlueprint(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    return PartialReparseDryRunIsolatedCopyBlueprint.fromJavaCompilerServiceCopy(
        DEFAULT_NOT_AVAILABLE_REASON);
  }

  @NonNull
  PartialReparseDryRunIsolatedSessionAssembly createSessionAssembly(
      @NonNull CompilationRequest request,
      @NonNull PartialReparseEligibility eligibility,
      @NonNull PartialReparseDryRunReport attemptReport) {
    final PartialReparseDryRunIsolatedCopyBlueprint blueprint =
        createCopyBlueprint(request, eligibility, attemptReport);
    return PartialReparseDryRunIsolatedSessionAssembly.fromCopyBlueprint(
        blueprint, blueprint.reason);
  }
}
