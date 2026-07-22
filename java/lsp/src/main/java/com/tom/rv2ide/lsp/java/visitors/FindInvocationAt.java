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

package com.tom.rv2ide.lsp.java.visitors;

import com.tom.rv2ide.common.logging.IdeLogConfig;
import com.tom.rv2ide.progress.ICancelChecker;
import openjdk.source.tree.CompilationUnitTree;
import openjdk.source.tree.MethodInvocationTree;
import openjdk.source.tree.NewClassTree;
import openjdk.source.util.JavacTask;
import openjdk.source.util.SourcePositions;
import openjdk.source.util.TreePath;
import openjdk.source.util.TreePathScanner;
import openjdk.source.util.Trees;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FindInvocationAt extends TreePathScanner<TreePath, Long> {

  private static final Logger LOG = LoggerFactory.getLogger(FindInvocationAt.class);

  private final JavacTask task;
  private final ICancelChecker cancelChecker;
  private CompilationUnitTree root;

  public FindInvocationAt(JavacTask task, ICancelChecker cancelChecker) {
    this.task = task;
    this.cancelChecker = cancelChecker;
  }

  @Override
  public TreePath visitCompilationUnit(CompilationUnitTree t, Long find) {
    cancelChecker.abortIfCancelled();
    root = t;
    return reduce(super.visitCompilationUnit(t, find), getCurrentPath());
  }

  @Override
  public TreePath visitMethodInvocation(MethodInvocationTree t, Long find) {
    cancelChecker.abortIfCancelled();
    SourcePositions pos = Trees.instance(task).getSourcePositions();
    long start = pos.getEndPosition(root, t.getMethodSelect()) + 1;
    long end = pos.getEndPosition(root, t) - 1;
    if (start <= find && find <= end) {
      return reduce(super.visitMethodInvocation(t, find), getCurrentPath());
    }
    return super.visitMethodInvocation(t, find);
  }

  @Override
  public TreePath visitNewClass(NewClassTree t, Long find) {
    cancelChecker.abortIfCancelled();
    SourcePositions pos = Trees.instance(task).getSourcePositions();
    long identifierEnd = pos.getEndPosition(root, t.getIdentifier());
    long invocationEnd = pos.getEndPosition(root, t);
    long start = identifierEnd + 1;
    long end = invocationEnd - 1;
    final boolean matched = start <= find && find <= end;
    if (IdeLogConfig.shouldLogInfo()) {
      LOG.info(
          "Signature invocation NewClass identifier={} identifierKind={} identifierEnd={} invocationEnd={} rangeStart={} rangeEnd={} cursor={} matched={} arguments={}",
          t.getIdentifier(),
          t.getIdentifier().getKind(),
          identifierEnd,
          invocationEnd,
          start,
          end,
          find,
          matched,
          t.getArguments().size());
    }
    if (matched) {
      return reduce(super.visitNewClass(t, find), getCurrentPath());
    }
    return super.visitNewClass(t, find);
  }

  @Override
  public TreePath reduce(TreePath a, TreePath b) {
    cancelChecker.abortIfCancelled();
    if (a != null) {
      return a;
    }
    return b;
  }
}
