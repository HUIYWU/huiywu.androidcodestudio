/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml

import com.google.common.truth.Truth.assertThat
import junit.framework.TestCase

class XmlWorkspaceFilesTest : TestCase() {

  fun testAllowsXmlWithWorkspaceOwningModule() {
    assertThat(isWorkspaceXmlFile(isXml = true) { true }).isTrue()
  }

  fun testRejectsExternalXmlWithoutWorkspaceOwningModule() {
    assertThat(isWorkspaceXmlFile(isXml = true) { false }).isFalse()
  }

  fun testRejectsNonXmlEvenWhenModuleOwnsIt() {
    assertThat(isWorkspaceXmlFile(isXml = false) { true }).isFalse()
  }
}