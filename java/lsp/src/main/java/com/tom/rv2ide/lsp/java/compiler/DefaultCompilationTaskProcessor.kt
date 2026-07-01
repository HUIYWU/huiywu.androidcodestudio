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

package com.tom.rv2ide.lsp.java.compiler

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.utils.StopWatch
import java.util.function.Consumer
import openjdk.source.tree.CompilationUnitTree
import openjdk.tools.javac.api.JavacTaskImpl

/**
 * Default implementation of [CompilationTaskProcessor].
 *
 * @author Akash Yadav
 */
class DefaultCompilationTaskProcessor : CompilationTaskProcessor {

  // working-set debugSourceSummary removed

  override fun process(task: JavacTaskImpl, processCompilationUnit: Consumer<CompilationUnitTree>) {
    val watch = StopWatch("Process compilation task")
    log.warn(
        "[TRACE_TASK_PROCESSOR] process start taskClass={} contextPresent={}",
        task.javaClass.name,
        task.context != null,
    )
    val trees = try {
      task.parse().also {
        if (IdeLogConfig.shouldLogDebug()) {
          watch.lapFromLast("Parsed treees")
        }
      }
    } catch (err: Throwable) {
      if (IdeLogConfig.shouldLogWarn()) {
        log.warn(
            "DefaultCompilationTaskProcessor.parse failed taskClass={} contextPresent={}",
            task.javaClass.name,
            task.context != null,
            err,
        )
      }
      throw err
    }

    var treeCount = 0
    trees.forEach {
      treeCount++
      log.warn(
          "[TRACE_TASK_PROCESSOR] parsed tree index={} uri={}",
          treeCount,
          it.sourceFile?.toUri(),
      )
      processCompilationUnit.accept(it)
    }
    log.warn(
        "[TRACE_TASK_PROCESSOR] process parsed treeCount={} taskClass={} contextPresent={}",
        treeCount,
        task.javaClass.name,
        task.context != null,
    )
    if (IdeLogConfig.shouldLogDebug()) {
      watch.lapFromLast("Processed trees")
    }

    //    val entered = JavacTaskUtil.enterTrees(task, trees)
    //    watch.lapFromLast("Entered trees")
    //
    //    val analyzed = JavacTaskUtil.analyze(task, entered)
    try {
      task.analyze()
      if (IdeLogConfig.shouldLogDebug()) {
        watch.lapFromLast("Analyzed all trees")
      }
    } catch (err: Throwable) {
      if (IdeLogConfig.shouldLogWarn()) {
        log.warn(
            "DefaultCompilationTaskProcessor.analyze failed taskClass={} contextPresent={} treeCount={}",
            task.javaClass.name,
            task.context != null,
            treeCount,
            err,
        )
      }
      throw err
    }
  }

  companion object {
    private val log = org.slf4j.LoggerFactory.getLogger(DefaultCompilationTaskProcessor::class.java)
  }
}
