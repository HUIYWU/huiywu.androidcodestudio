package com.tom.rv2ide.lsp.java.compiler

import com.tom.rv2ide.eventbus.events.editor.ChangeType
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.lsp.java.models.CompilationRequest
import com.tom.rv2ide.lsp.java.models.PartialReparseRequest
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import java.net.URI
import java.nio.file.Paths
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartialReparseDeciderTest {

  @Test
  fun returnsFullRecompile_whenUserPreferenceDisablesPartialReparse() {
    val decision =
        TestablePartialReparseDecider(
                userEnabled = false,
                featureEnabled = true,
                dryRunEnabled = false,
            )
            .decide(eligible())

    assertEquals(PartialReparseDecision.Action.FULL_RECOMPILE, decision.action)
    assertEquals("partial reparse disabled in Java editor preferences", decision.reason)
  }

  @Test
  fun returnsFullRecompile_whenFeatureFlagsDisablePartialReparse() {
    val decision =
        TestablePartialReparseDecider(
                userEnabled = true,
                featureEnabled = false,
                dryRunEnabled = false,
            )
            .decide(eligible())

    assertEquals(PartialReparseDecision.Action.FULL_RECOMPILE, decision.action)
    assertEquals("partial reparse disabled by feature flags", decision.reason)
  }

  @Test
  fun returnsDryRun_whenDryRunIsEnabled() {
    val decision =
        TestablePartialReparseDecider(
                userEnabled = true,
                featureEnabled = false,
                dryRunEnabled = true,
            )
            .decide(eligible())

    assertEquals(PartialReparseDecision.Action.DRY_RUN_PARTIAL_REPARSE, decision.action)
    assertEquals("partial reparse dry-run enabled", decision.reason)
  }

  @Test
  fun returnsTryPartial_whenEligibleAndFeatureEnabled() {
    val decision =
        TestablePartialReparseDecider(
                userEnabled = true,
                featureEnabled = true,
                dryRunEnabled = false,
            )
            .decide(eligible())

    assertEquals(PartialReparseDecision.Action.TRY_PARTIAL_REPARSE, decision.action)
    assertEquals("eligible for partial reparse", decision.reason)
  }

  @Test
  fun returnsFullRecompile_whenLatestChangeRangeIsUnknown() {
    val decision =
        TestablePartialReparseDecider(
                userEnabled = true,
                featureEnabled = true,
                dryRunEnabled = false,
            )
            .decide(eligible(latestChangeRange = null))

    assertEquals(PartialReparseDecision.Action.FULL_RECOMPILE, decision.action)
    assertEquals("latest document change range is unknown", decision.reason)
  }

  @Test
  fun returnsFullRecompile_whenChangeDeltaTooLarge() {
    val decision =
        TestablePartialReparseDecider(
                userEnabled = true,
                featureEnabled = true,
                dryRunEnabled = false,
            )
            .decide(eligible(changeDeltaWithinLimit = false))

    assertEquals(PartialReparseDecision.Action.FULL_RECOMPILE, decision.action)
    assertEquals("document change delta is too large for partial reparse", decision.reason)
  }

  @Test
  fun returnsFullRecompile_whenNoPartialRequest() {
    val decision =
        TestablePartialReparseDecider(
                userEnabled = true,
                featureEnabled = true,
                dryRunEnabled = false,
            )
            .decide(eligible(hasPartialRequest = false))

    assertEquals(PartialReparseDecision.Action.FULL_RECOMPILE, decision.action)
    assertEquals("no partial request", decision.reason)
  }

  @Test
  fun returnsFullRecompile_whenRequestIsNull() {
    val decision =
        TestablePartialReparseDecider(
                userEnabled = true,
                featureEnabled = true,
                dryRunEnabled = false,
            )
            .decide(
                buildEligibility(
                    request = null,
                    needsRecompilation = false,
                    changeValidForReparse = true,
                    changeDeltaWithinLimit = true,
                    sourceCount = 1,
                    hasPartialRequest = false,
                    cursor = -1L,
                    contentsLength = -1,
                    changeDelta = 0,
                    newCursorPosition = null,
                    latestChangeRange = null,
                )
            )

    assertEquals(PartialReparseDecision.Action.FULL_RECOMPILE, decision.action)
    assertEquals("request is null", decision.reason)
  }

  @Test
  fun returnsFullRecompile_whenCachedCompileIsMissingOrClosed() {
    val decision =
        TestablePartialReparseDecider(
                userEnabled = true,
                featureEnabled = true,
                dryRunEnabled = false,
            )
            .decide(eligible(needsRecompilation = true))

    assertEquals(PartialReparseDecision.Action.FULL_RECOMPILE, decision.action)
    assertEquals("cached compile is missing or closed", decision.reason)
  }

  @Test
  fun returnsFullRecompile_whenPartialReparseRequiresExactlyOneSource() {
    val decision =
        TestablePartialReparseDecider(
                userEnabled = true,
                featureEnabled = true,
                dryRunEnabled = false,
            )
            .decide(eligible(sourceCount = 2))

    assertEquals(PartialReparseDecision.Action.FULL_RECOMPILE, decision.action)
    assertEquals("partial reparse requires exactly one source", decision.reason)
  }

  @Test
  fun returnsFullRecompile_whenCursorIsInvalid() {
    val decision =
        TestablePartialReparseDecider(
                userEnabled = true,
                featureEnabled = true,
                dryRunEnabled = false,
            )
            .decide(eligible(cursor = -1L))

    assertEquals(PartialReparseDecision.Action.FULL_RECOMPILE, decision.action)
    assertEquals("invalid partial reparse cursor", decision.reason)
  }

  @Test
  fun returnsFullRecompile_whenContentsIsNull() {
    val decision =
        TestablePartialReparseDecider(
                userEnabled = true,
                featureEnabled = true,
                dryRunEnabled = false,
            )
            .decide(eligible(contentsLength = -1))

    assertEquals(PartialReparseDecision.Action.FULL_RECOMPILE, decision.action)
    assertEquals("partial reparse contents is null", decision.reason)
  }

  @Test
  fun returnsFullRecompile_whenDocumentChangeIsNotValidForPartialReparse() {
    val decision =
        TestablePartialReparseDecider(
                userEnabled = true,
                featureEnabled = true,
                dryRunEnabled = false,
            )
            .decide(eligible(changeValidForReparse = false))

    assertEquals(PartialReparseDecision.Action.FULL_RECOMPILE, decision.action)
    assertEquals("document change is not valid for partial reparse", decision.reason)
  }

  @Test
  fun eligibilityFactory_marksLargeChangeDeltaAsNotWithinLimit() {
    val incrementalState = JavaIncrementalState()
    incrementalState.onDocumentChange(
        changeEvent(
            delta = JavaLspFeatureFlags.MAX_PARTIAL_REPARSE_CHANGE_DELTA + 1,
            range = Range(Position(0, 0, 0), Position(0, 1, 1)),
        )
    )

    val eligibility =
        PartialReparseEligibility.from(
            CompilationRequest(
                sources = listOf(FakeSourceFile()),
                partialRequest = PartialReparseRequest(1L, "class A {}"),
            ),
            false,
            incrementalState,
        )

    assertEquals(false, eligibility.changeDeltaWithinLimit)
    assertEquals(JavaLspFeatureFlags.MAX_PARTIAL_REPARSE_CHANGE_DELTA + 1, eligibility.changeDelta)
  }

  @Test
  fun eligibilityFactory_marksMissingContentsAsInvalidLength() {
    val incrementalState = JavaIncrementalState()

    val eligibility =
        PartialReparseEligibility.from(
            CompilationRequest(sources = listOf(FakeSourceFile()), partialRequest = null),
            false,
            incrementalState,
        )

    assertEquals(false, eligibility.hasPartialRequest)
    assertEquals(-1, eligibility.contentsLength)
    assertEquals(-1L, eligibility.cursor)
    assertNull(eligibility.latestChangeRange)
  }

  private fun eligible(
      hasPartialRequest: Boolean = true,
      needsRecompilation: Boolean = false,
      changeValidForReparse: Boolean = true,
      changeDeltaWithinLimit: Boolean = true,
      sourceCount: Int = 1,
      cursor: Long = if (hasPartialRequest) 1L else -1L,
      contentsLength: Int = if (hasPartialRequest) 10 else -1,
      latestChangeRange: Range? = Range(Position(0, 0, 0), Position(0, 5, 5)),
  ): PartialReparseEligibility {
    val request =
        if (hasPartialRequest) {
          CompilationRequest(
              sources = listOf(FakeSourceFile()),
              partialRequest = PartialReparseRequest(1L, "class A {}"),
          )
        } else {
          CompilationRequest(sources = listOf(FakeSourceFile()), partialRequest = null)
        }

    return buildEligibility(
        request = request,
        needsRecompilation = needsRecompilation,
        changeValidForReparse = changeValidForReparse,
        changeDeltaWithinLimit = changeDeltaWithinLimit,
        sourceCount = sourceCount,
        hasPartialRequest = hasPartialRequest,
        cursor = cursor,
        contentsLength = contentsLength,
        changeDelta = 1,
        newCursorPosition = Position(0, 1, 1),
        latestChangeRange = latestChangeRange,
    )
  }

  private fun buildEligibility(
      request: CompilationRequest?,
      needsRecompilation: Boolean,
      changeValidForReparse: Boolean,
      changeDeltaWithinLimit: Boolean,
      sourceCount: Int,
      hasPartialRequest: Boolean,
      cursor: Long,
      contentsLength: Int,
      changeDelta: Int,
      newCursorPosition: Position?,
      latestChangeRange: Range?,
  ): PartialReparseEligibility {
    val ctor = PartialReparseEligibility::class.java.declaredConstructors.single()
    ctor.isAccessible = true
    return ctor.newInstance(
        request,
        needsRecompilation,
        changeValidForReparse,
        changeDeltaWithinLimit,
        sourceCount,
        hasPartialRequest,
        cursor,
        contentsLength,
        changeDelta,
        newCursorPosition,
        latestChangeRange,
    ) as PartialReparseEligibility
  }

  private fun changeEvent(delta: Int, range: Range): DocumentChangeEvent {
    return DocumentChangeEvent(
        Paths.get("/tmp/A.java"),
        "x",
        null,
        1,
        ChangeType.INSERT,
        delta,
        range,
    )
  }

  private class TestablePartialReparseDecider(
      private val userEnabled: Boolean,
      private val featureEnabled: Boolean,
      private val dryRunEnabled: Boolean,
  ) : PartialReparseDecider() {
    override fun isPartialReparseEnabledByUser(): Boolean = userEnabled

    override fun isPartialReparseFeatureEnabled(): Boolean = featureEnabled

    override fun isPartialReparseDryRunEnabled(): Boolean = dryRunEnabled
  }

  private class FakeSourceFile :
      SimpleJavaFileObject(URI.create("string:///A.java"), JavaFileObject.Kind.SOURCE) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = "class A {}"
  }
}