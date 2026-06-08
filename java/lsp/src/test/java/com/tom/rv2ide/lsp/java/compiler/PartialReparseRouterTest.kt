package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Test

class PartialReparseRouterTest {

  @Test
  fun routesFullRecompileDecisionToFullRecompileCallbackOnly() {
    val calls = Calls()

    val branch =
        PartialReparseRouter()
            .route(
                PartialReparseDecision.fullRecompile("test"),
                { calls.fullRecompile++ },
                { calls.dryRun++ },
                { calls.tryPartial++ },
            )

    assertEquals(PartialReparseRouter.Branch.FULL_RECOMPILE, branch)
    assertEquals(1, calls.fullRecompile)
    assertEquals(0, calls.dryRun)
    assertEquals(0, calls.tryPartial)
  }

  @Test
  fun routesDryRunDecisionToDryRunCallbackOnly() {
    val calls = Calls()

    val branch =
        PartialReparseRouter()
            .route(
                PartialReparseDecision.dryRun("test"),
                { calls.fullRecompile++ },
                { calls.dryRun++ },
                { calls.tryPartial++ },
            )

    assertEquals(PartialReparseRouter.Branch.DRY_RUN_PARTIAL_REPARSE, branch)
    assertEquals(0, calls.fullRecompile)
    assertEquals(1, calls.dryRun)
    assertEquals(0, calls.tryPartial)
  }

  @Test
  fun routesTryPartialDecisionToTryPartialCallbackOnly() {
    val calls = Calls()

    val branch =
        PartialReparseRouter()
            .route(
                PartialReparseDecision.tryPartial("test"),
                { calls.fullRecompile++ },
                { calls.dryRun++ },
                { calls.tryPartial++ },
            )

    assertEquals(PartialReparseRouter.Branch.TRY_PARTIAL_REPARSE, branch)
    assertEquals(0, calls.fullRecompile)
    assertEquals(0, calls.dryRun)
    assertEquals(1, calls.tryPartial)
  }

  private class Calls {
    var fullRecompile = 0
    var dryRun = 0
    var tryPartial = 0
  }
}