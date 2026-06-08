package com.tom.rv2ide.lsp.java.compiler

import com.tom.rv2ide.lsp.java.models.CompilationRequest
import com.tom.rv2ide.lsp.java.models.PartialReparseRequest
import java.net.URI
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import org.junit.Assert.assertNull
import org.junit.Test

class PartialReparseDryRunAttemptProviderTest {

  @Test
  fun createAttemptReturnsNullByDefault() {
    val request = CompilationRequest(listOf(FakeSourceFile()), PartialReparseRequest(1L, "class A {}"))
    val eligibility = PartialReparseEligibility.from(request, false, JavaIncrementalState())

    val attempt = PartialReparseDryRunAttemptProvider().createAttempt(request, eligibility)

    assertNull(attempt)
  }

  private class FakeSourceFile :
      SimpleJavaFileObject(URI.create("string:///A.java"), JavaFileObject.Kind.SOURCE) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = "class A {}"
    override fun getLastModified(): Long = 1L
  }
}