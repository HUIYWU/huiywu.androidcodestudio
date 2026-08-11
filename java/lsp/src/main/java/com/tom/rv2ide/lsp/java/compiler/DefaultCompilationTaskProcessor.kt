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
    val trees = try {
      val parseStartedNs = System.nanoTime()
      val parsed = task.parse().toList()
      if (IdeLogConfig.shouldLogInfo()) {
        log.info(
            "Javac stage=parse durationMs={} treeCount={} taskClass={} contextPresent={}",
            (System.nanoTime() - parseStartedNs) / 1_000_000L,
            parsed.size,
            task.javaClass.simpleName,
            task.context != null,
        )
      }
      parsed.also {
        if (IdeLogConfig.shouldLogDebug()) {
          watch.lapFromLast("Parsed trees")
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
    val processTreesStartedNs = System.nanoTime()
    trees.forEach {
      treeCount++
      processCompilationUnit.accept(it)
    }
    if (IdeLogConfig.shouldLogInfo()) {
      log.info(
          "Javac stage=process-trees durationMs={} treeCount={}",
          (System.nanoTime() - processTreesStartedNs) / 1_000_000L,
          treeCount,
      )
    }
    if (IdeLogConfig.shouldLogDebug()) {
      watch.lapFromLast("Processed trees")
    }

    //    val entered = JavacTaskUtil.enterTrees(task, trees)
    //    watch.lapFromLast("Entered trees")
    //
    //    val analyzed = JavacTaskUtil.analyze(task, entered)
    try {
      val analyzeStartedNs = System.nanoTime()
      task.analyze()
      if (IdeLogConfig.shouldLogInfo()) {
        log.info(
            "Javac stage=analyze durationMs={} treeCount={} contextPresent={}",
            (System.nanoTime() - analyzeStartedNs) / 1_000_000L,
            treeCount,
            task.context != null,
        )
      }
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
