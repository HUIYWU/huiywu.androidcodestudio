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
package com.tom.rv2ide.lsp.java.providers

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.lsp.java.compiler.CompilationTaskProcessor
import com.tom.rv2ide.utils.StopWatch
import java.util.function.Consumer
import openjdk.source.tree.CompilationUnitTree
import openjdk.tools.javac.api.JavacTaskImpl
import org.slf4j.LoggerFactory

/**
 * Diagnostics should still be published when javac's analyze phase fails after parse produced roots
 * and compiler diagnostics. Falling back to compiler diagnostics is better than returning NO_UPDATE.
 */
internal class DiagnosticCompilationTaskProcessor : CompilationTaskProcessor {

  override fun process(task: JavacTaskImpl, processCompilationUnit: Consumer<CompilationUnitTree>) {
    val watch = StopWatch("Process diagnostic compilation task")
    val trees = task.parse().also {
      if (IdeLogConfig.shouldLogDebug()) {
        watch.lapFromLast("Parsed trees")
      }
    }

    var treeCount = 0
    trees.forEach {
      treeCount++
      processCompilationUnit.accept(it)
    }
    if (IdeLogConfig.shouldLogDebug()) {
      watch.lapFromLast("Processed trees")
    }

    try {
      task.analyze()
      if (IdeLogConfig.shouldLogDebug()) {
        watch.lapFromLast("Analyzed all trees")
      }
    } catch (err: Throwable) {
      if (IdeLogConfig.shouldLogWarn()) {
        log.warn(
            "DiagnosticCompilationTaskProcessor.analyze failed; keeping parsed roots and compiler diagnostics taskClass={} contextPresent={} treeCount={}",
            task.javaClass.name,
            task.context != null,
            treeCount,
            err,
        )
      }
    }
  }

  companion object {
    private val log = LoggerFactory.getLogger(DiagnosticCompilationTaskProcessor::class.java)
  }
}
