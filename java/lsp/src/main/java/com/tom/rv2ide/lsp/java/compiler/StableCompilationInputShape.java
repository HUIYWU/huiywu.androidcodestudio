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

/**
 * Immutable shape of the source inputs accepted by one completed stable compilation.
 *
 * <p>This is observation metadata only. It contains counts, not source text or compiler state, and
 * it must never authorize reuse or candidate execution for the compilation that produced it.
 */
public final class StableCompilationInputShape {

  private final int requestedSourceCount;
  private final int effectiveSourceCount;
  private final int kotlinAbiStubCount;
  private final int additionalJavaSourceCount;

  public StableCompilationInputShape(
      int requestedSourceCount,
      int effectiveSourceCount,
      int kotlinAbiStubCount,
      int additionalJavaSourceCount) {
    this.requestedSourceCount = requestedSourceCount;
    this.effectiveSourceCount = effectiveSourceCount;
    this.kotlinAbiStubCount = kotlinAbiStubCount;
    this.additionalJavaSourceCount = additionalJavaSourceCount;
  }

  public int getRequestedSourceCount() {
    return requestedSourceCount;
  }

  public int getEffectiveSourceCount() {
    return effectiveSourceCount;
  }

  public int getKotlinAbiStubCount() {
    return kotlinAbiStubCount;
  }

  public int getAdditionalJavaSourceCount() {
    return additionalJavaSourceCount;
  }

  /**
   * True only after stable compilation proved that its final javac source input stayed one Java
   * source without Kotlin ABI stubs or javac-requested additional Java sources.
   */
  public boolean isProvenSingleJavaSourceWithoutKotlinStubs() {
    return requestedSourceCount == 1
        && effectiveSourceCount == 1
        && kotlinAbiStubCount == 0
        && additionalJavaSourceCount == 0;
  }
}
