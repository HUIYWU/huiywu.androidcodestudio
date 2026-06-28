/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.java.compiler;

import androidx.annotation.NonNull;
import com.tom.rv2ide.common.logging.IdeLogConfig;
import com.tom.rv2ide.javac.services.partial.DiagnosticListenerImpl;
import java.nio.file.Path;
import java.util.List;
import jdkx.tools.Diagnostic;
import jdkx.tools.JavaFileObject;
import openjdk.source.tree.CompilationUnitTree;
import openjdk.tools.javac.api.JavacTaskImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompileTask implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(CompileTask.class);

  public final JavacTaskImpl task;

  public final List<CompilationUnitTree> roots;
  public final List<Diagnostic<? extends JavaFileObject>> diagnostics;
  public final CompileBatch compileBatch;
  public final DiagnosticListenerImpl diagnosticListener;

  public CompileTask(
      @NonNull CompileBatch compileBatch, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
    this.compileBatch = compileBatch;
    this.task = compileBatch.task;
    this.roots = compileBatch.roots;
    this.diagnostics = diagnostics;
    this.diagnosticListener = compileBatch.diagnosticListener;
  }

  public CompilationUnitTree root() {
    if (roots.size() != 1) {
      throw new RuntimeException("No compilation units found. Roots: " + roots.size());
    }
    return roots.get(0);
  }

  public CompilationUnitTree root(Path file) {
    for (CompilationUnitTree root : roots) {
      if (root.getSourceFile().toUri().equals(file.toUri())) {
        return root;
      }
    }
    throw new RuntimeException("Compilation unit not found");
  }

  public CompilationUnitTree root(JavaFileObject file) {
    for (CompilationUnitTree root : roots) {
      if (root.getSourceFile().toUri().equals(file.toUri())) {
        return root;
      }
    }
    throw new RuntimeException("Compilation unit not found");
  }
  @Override
  public void close() {
    final Runtime runtimeBefore = Runtime.getRuntime();
    final long usedBefore = runtimeBefore.totalMemory() - runtimeBefore.freeMemory();
    LOG.info(
        "CompileTask.close start taskHash={} compileBatchPresent={} roots={} diagnostics={} usedMemoryBytes={}",
        System.identityHashCode(this),
        compileBatch != null,
        roots == null ? -1 : roots.size(),
        diagnostics == null ? -1 : diagnostics.size(),
        usedBefore);

    if (compileBatch != null) {
      compileBatch.close();
    }
    final Runtime runtimeAfter = Runtime.getRuntime();
    final long usedAfter = runtimeAfter.totalMemory() - runtimeAfter.freeMemory();
    LOG.info(
        "CompileTask.close end taskHash={} usedMemoryBytes={}",
        System.identityHashCode(this),
        usedAfter);

  }
}

