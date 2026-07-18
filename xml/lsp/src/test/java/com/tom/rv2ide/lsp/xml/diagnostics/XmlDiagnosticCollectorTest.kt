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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.xml.diagnostics

import com.google.common.truth.Truth.assertThat
import junit.framework.TestCase

class XmlDiagnosticCollectorTest : TestCase() {

  fun testBoundsDeduplicatesAndSortsRanges() {
    val collector = XmlDiagnosticCollector("first\nsecond")

    collector.errorRange("LATE", "late", 8, 200)
    collector.errorRange("EARLY", "early", -5, 5)
    collector.errorRange("EARLY", "duplicate", 0, 5)

    val diagnostics = collector.build()
    assertThat(diagnostics.map { it.code }).containsExactly("EARLY", "LATE").inOrder()
    assertThat(diagnostics[0].range.start.line).isEqualTo(0)
    assertThat(diagnostics[0].range.start.column).isEqualTo(0)
    assertThat(diagnostics[0].range.end.line).isEqualTo(0)
    assertThat(diagnostics[0].range.end.column).isEqualTo(5)
    assertThat(diagnostics[1].range.start.line).isEqualTo(1)
    assertThat(diagnostics[1].range.start.column).isEqualTo(2)
    assertThat(diagnostics[1].range.end.line).isEqualTo(1)
    assertThat(diagnostics[1].range.end.column).isEqualTo(6)
  }

  fun testLimitsDiagnosticsPerFile() {
    val text = "x".repeat(150)
    val collector = XmlDiagnosticCollector(text)
    repeat(150) { index ->
      collector.errorRange("CODE_$index", "message", index, index + 1)
    }

    assertThat(collector.build()).hasSize(100)
  }
}