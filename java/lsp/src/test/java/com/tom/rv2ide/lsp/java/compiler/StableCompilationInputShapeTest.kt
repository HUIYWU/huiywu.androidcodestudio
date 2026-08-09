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

package com.tom.rv2ide.lsp.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableCompilationInputShapeTest {
  @Test
  fun provesOnlyAnUnexpandedSingleJavaSource() {
    val shape =
        StableCompilationInputShape(
            1,
            1,
            0,
            0,
        )

    assertEquals(1, shape.requestedSourceCount)
    assertEquals(1, shape.effectiveSourceCount)
    assertEquals(0, shape.kotlinAbiStubCount)
    assertEquals(0, shape.additionalJavaSourceCount)
    assertTrue(shape.isProvenSingleJavaSourceWithoutKotlinStubs)
  }

  @Test
  fun rejectsEveryExpandedOrSyntheticShape() {
    assertFalse(
        shape(1, 2, kotlinAbiStubCount = 1, additionalJavaSourceCount = 0)
            .isProvenSingleJavaSourceWithoutKotlinStubs,
    )
    assertFalse(
        shape(1, 2, kotlinAbiStubCount = 0, additionalJavaSourceCount = 1)
            .isProvenSingleJavaSourceWithoutKotlinStubs,
    )
    assertFalse(
        shape(2, 2, kotlinAbiStubCount = 0, additionalJavaSourceCount = 0)
            .isProvenSingleJavaSourceWithoutKotlinStubs,
    )
  }

  private fun shape(
      requestedSourceCount: Int,
      effectiveSourceCount: Int,
      kotlinAbiStubCount: Int,
      additionalJavaSourceCount: Int,
  ) =
      StableCompilationInputShape(
          requestedSourceCount,
          effectiveSourceCount,
          kotlinAbiStubCount,
          additionalJavaSourceCount,
      )
}
