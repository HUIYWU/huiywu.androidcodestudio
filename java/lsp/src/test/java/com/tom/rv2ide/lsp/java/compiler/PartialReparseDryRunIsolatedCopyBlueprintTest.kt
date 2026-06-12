package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedCopyBlueprintTest {

  @Test
  fun fromJavaCompilerServiceCopyCapturesCurrentCopyContract() {
    val blueprint =
        PartialReparseDryRunIsolatedCopyBlueprint.fromJavaCompilerServiceCopy("copy blueprint")

    assertEquals("copy blueprint", blueprint.reason)
    assertTrue(blueprint.sharesSourceFileManagerWithLiveCompiler)
    assertTrue(blueprint.requiresFreshReusableCompiler)
    assertTrue(blueprint.cachedCompileMustStartEmpty)
    assertTrue(blueprint.requiresExplicitDestroy)
  }
}