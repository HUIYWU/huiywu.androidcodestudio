package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReparseDryRunIsolatedSessionAssemblyTest {

  @Test
  fun fromCopyBlueprintCapturesCurrentCopiedCompilerAssemblyContract() {
    val blueprint =
        PartialReparseDryRunIsolatedCopyBlueprint.fromJavaCompilerServiceCopy("copy blueprint")

    val assembly =
        PartialReparseDryRunIsolatedSessionAssembly.fromCopyBlueprint(
            blueprint,
            "assembly",
        )

    assertEquals("assembly", assembly.reason)
    assertTrue(assembly.usesJavaCompilerServiceCopyMethod)
    assertTrue(assembly.reusesLiveModuleReference)
    assertTrue(assembly.reusesLiveSourceFileManager)
    assertTrue(assembly.createsFreshReusableCompiler)
    assertTrue(assembly.startsWithEmptyCachedCompile)
    assertTrue(assembly.clearsCopiedDiagnostics)
    assertTrue(assembly.clearsCopiedModificationCache)
    assertTrue(assembly.requiresExplicitDestroy)
  }
}