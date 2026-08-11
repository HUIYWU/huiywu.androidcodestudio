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
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.function.Consumer
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.util.TaskEvent
import openjdk.source.util.TaskListener
import openjdk.tools.javac.api.JavacTaskImpl
import openjdk.tools.javac.comp.Todo

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
    val rootSources = trees.mapNotNull { it.sourceFile?.toUri()?.toString() }.toSet()
    val analyzeListener = AnalyzeTaskListener(task, rootSources)
    try {
      task.addTaskListener(analyzeListener)
      val memoryBeforeBytes = usedHeapBytes()
      val analyzeStartedNs = System.nanoTime()
      task.analyze()
      logTodoSnapshot(task, "after")
      if (IdeLogConfig.shouldLogInfo()) {
        val memoryAfterBytes = usedHeapBytes()
        log.info(
            "Javac stage=analyze durationMs={} treeCount={} contextPresent={} heapBeforeMiB={} heapAfterMiB={} heapDeltaMiB={} analyzedTypeCount={}",
            (System.nanoTime() - analyzeStartedNs) / 1_000_000L,
            treeCount,
            task.context != null,
            memoryBeforeBytes / MEBIBYTE,
            memoryAfterBytes / MEBIBYTE,
            (memoryAfterBytes - memoryBeforeBytes) / MEBIBYTE,
            analyzeListener.completedTypeCount,
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
    } finally {
      task.removeTaskListener(analyzeListener)
    }
  }

  private inner class AnalyzeTaskListener(
      private val task: JavacTaskImpl,
      private val rootSources: Set<String>,
  ) : TaskListener {
    private val stack = ArrayDeque<AnalyzeFrame>()
    private var todoLogged = false
    private var enterStartedNs: Long = 0
    var completedTypeCount = 0
      private set

    override fun started(event: TaskEvent) {
      when (event.kind) {
        TaskEvent.Kind.ENTER -> {
          enterStartedNs = System.nanoTime()
        }
        TaskEvent.Kind.ANALYZE -> {
          if (!todoLogged) {
            todoLogged = true
            logTodoSnapshot(task, "analyze-start")
          }
          stack.addLast(AnalyzeFrame(event, System.nanoTime()))
        }
        else -> Unit
      }
    }

    override fun finished(event: TaskEvent) {
      if (event.kind == TaskEvent.Kind.ENTER) {
        logEnter(event)
        return
      }
      if (event.kind != TaskEvent.Kind.ANALYZE) return
      completedTypeCount++
      val finishedNs = System.nanoTime()
      val frame = if (stack.isEmpty()) null else stack.removeLast()
      if (frame == null) return
      val inclusiveNs = finishedNs - frame.startedNs
      val exclusiveNs = inclusiveNs - frame.childNs
      if (!stack.isEmpty()) {
        stack.peekLast().childNs += inclusiveNs
      }
      if (IdeLogConfig.shouldLogInfo()) {
        log.info(
            "Javac stage=analyze-type durationMs={} exclusiveMs={} depth={} type={} source={}",
            inclusiveNs / 1_000_000L,
            exclusiveNs / 1_000_000L,
            stack.size,
            event.typeElement?.qualifiedName ?: "<unknown-type>",
            event.sourceFile?.toUri() ?: "<unknown-source>",
        )
      }
    }

    private fun logEnter(event: TaskEvent) {
      if (!IdeLogConfig.shouldLogInfo()) return
      val source = event.sourceFile?.toUri()?.toString() ?: "<unknown-source>"
      log.info(
          "Javac stage=enter durationMs={} rootSource={} source={} todoSize={}",
          (System.nanoTime() - enterStartedNs) / 1_000_000L,
          rootSources.contains(source),
          source,
          Todo.instance(task.context).size,
      )
    }
  }

  private class AnalyzeFrame(val event: TaskEvent, val startedNs: Long) {
    var childNs: Long = 0
  }

  private fun logTodoSnapshot(task: JavacTaskImpl, phase: String) {
    if (!IdeLogConfig.shouldLogInfo()) return
    val todo = Todo.instance(task.context)
    val entries = ArrayList<String>()
    for (env in todo) {
      val type = env.enclClass?.sym?.flatname?.toString() ?: "<unknown-type>"
      val source = env.toplevel?.sourcefile?.toUri()?.toString() ?: "<unknown-source>"
      entries.add("$type@$source")
    }
    log.info(
        "Javac stage=analyze-todo phase={} size={} entries={}",
        phase,
        entries.size,
        entries.joinToString(","),
    )
  }

  private fun usedHeapBytes(): Long {
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
  }

  companion object {
    private const val MEBIBYTE = 1024L * 1024L
    private val log = org.slf4j.LoggerFactory.getLogger(DefaultCompilationTaskProcessor::class.java)
  }
}
