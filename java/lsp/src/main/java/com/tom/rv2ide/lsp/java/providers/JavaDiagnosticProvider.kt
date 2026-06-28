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
import android.os.Process
import com.tom.rv2ide.common.logging.IdeLogConfig


import com.tom.rv2ide.lsp.java.JavaCompilerProvider
import com.tom.rv2ide.lsp.java.compiler.CompileTask
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService
import com.tom.rv2ide.lsp.java.models.PartialReparseRequest
import com.tom.rv2ide.lsp.java.providers.DiagnosticsProvider.findDiagnostics
import com.tom.rv2ide.lsp.java.utils.CancelChecker
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.progress.ProgressManager
import com.tom.rv2ide.progress.ProgressManager.Companion.abortIfCancelled
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.IProjectManager
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * Code analyzer for java source code.
 *
 * @author Akash Yadav
 */
class JavaDiagnosticProvider {

  private val analyzeTimestamps = mutableMapOf<Path, Instant>()
  private val cachedDiagnostics = mutableMapOf<Path, DiagnosticResult>()
  private val analyzeGeneration = AtomicLong(0)
  private var analyzing = AtomicBoolean(false)
  private var analyzingThread: AnalyzingThread? = null

  companion object {

    private val log = LoggerFactory.getLogger(JavaDiagnosticProvider::class.java)
  }

  fun analyze(file: Path): DiagnosticResult {

    val module =
        IProjectManager.getInstance().getWorkspace()?.findModuleForFile(file, false)
            ?: return DiagnosticResult.NO_UPDATE
    val compiler = JavaCompilerProvider.get(module)

    abortIfCancelled()

    if (IdeLogConfig.shouldLogDebug()) {
      log.debug("Analyzing: {}", file)
    }

    val modifiedAt = FileManager.getLastModified(file)
    val analyzedAt = analyzeTimestamps[file]

    if (analyzedAt?.isAfter(modifiedAt) == true) {
      if (IdeLogConfig.shouldLogDebug()) {
        log.debug("Using cached analyze results...")
      }
      return cachedDiagnostics[file] ?: DiagnosticResult.NO_UPDATE
    }

    analyzingThread?.let { analyzingThread ->
      if (analyzing.get()) {
        if (IdeLogConfig.shouldLogDebug()) {
          log.debug("Cancelling currently analyzing thread...")
        }
        ProgressManager.instance.cancel(analyzingThread)
        this.analyzingThread = null
      }
    }

    val requestedGeneration = analyzeGeneration.incrementAndGet()
    if (IdeLogConfig.shouldLogInfo()) {
      log.info("Analyze request scheduled generation={} file={}", requestedGeneration, file)
    }
    analyzing.set(true)

    val analyzingThread =
        AnalyzingThread(compiler, file, requestedGeneration).also {
          analyzingThread = it
          it.start()
          it.join()
        }

    return analyzingThread.result.also {
      this.analyzingThread = null
      if (requestedGeneration == analyzeGeneration.get()) {
        cachedDiagnostics[file] = it
        analyzeTimestamps[file] = Instant.now()
      } else if (IdeLogConfig.shouldLogInfo()) {
        log.info(
          "Analyze cache update skipped due to newer request requestedGeneration={} currentGeneration={} file={}",
          requestedGeneration,
          analyzeGeneration.get(),
          file,
        )
      }
    }
  }

  fun isAnalyzing(): Boolean {
    return this.analyzing.get()
  }

  fun cancel() {
    this.analyzingThread?.cancel()
  }

  fun clearTimestamp(file: Path) {
    analyzeTimestamps.remove(file)
    cachedDiagnostics.remove(file)
  }

  private fun doAnalyze(file: Path, task: CompileTask): DiagnosticResult {
    val result =
        if (!isTaskValid(task)) {
          // Do not use Collections.emptyList ()
          // The returned list is accessed and the list returned by Collections.emptyList()
          // throws exception when trying to access.
          if (IdeLogConfig.shouldLogInfo()) {
            log.info("Using cached diagnostics")
          }
          cachedDiagnostics[file] ?: DiagnosticResult.NO_UPDATE
        } else DiagnosticResult(file, findDiagnostics(task, file).sortedBy { it.range })
    return result.also {
      if (IdeLogConfig.shouldLogDebug()) {
        log.debug("Analyze file completed. Found {} diagnostic items", result.diagnostics.size)
      }
    }
  }

  private fun isTaskValid(task: CompileTask?): Boolean {
    abortIfCancelled()
    return task?.task != null && task.roots != null && task.roots.size > 0
  }

  inner class AnalyzingThread(
      val compiler: JavaCompilerService,
      val file: Path,
      val requestedGeneration: Long,
  ) : Thread("JavaAnalyzerThread") {

    var result: DiagnosticResult = DiagnosticResult.NO_UPDATE

    fun cancel() {
      ProgressManager.instance.cancel(this)
    }

    override fun run() {
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
      result =
          try {
                if (requestedGeneration != analyzeGeneration.get()) {
                  if (IdeLogConfig.shouldLogInfo()) {
                    log.info(
                      "Analyze skipped before compile due to newer request requestedGeneration={} currentGeneration={} file={}",
                      requestedGeneration,
                      analyzeGeneration.get(),
                      file,
                    )
                  }
                  return
                }
                val contents = com.tom.rv2ide.projects.FileManager.getDocumentContents(file)
                val partialRequest =
                    compiler.getIncrementalState().newCursorPosition?.let { position ->
                      try {
                        val cursor = position.requireIndex()
                        if (cursor >= 0) PartialReparseRequest(cursor.toLong(), contents) else null
                      } catch (_: IllegalArgumentException) {
                        null
                      }
                    }
                if (requestedGeneration != analyzeGeneration.get()) {
                  if (IdeLogConfig.shouldLogInfo()) {
                    log.info(
                      "Analyze skipped after snapshot due to newer request requestedGeneration={} currentGeneration={} file={}",
                      requestedGeneration,
                      analyzeGeneration.get(),
                      file,
                    )
                  }
                  return
                }
                compiler
                    .compile(
                        com.tom.rv2ide.lsp.java.models.CompilationRequest(
                            listOf(
                                com.tom.rv2ide.lsp.java.compiler.SourceFileObject(
                                    file,
                                    contents,
                                    com.tom.rv2ide.projects.FileManager.getLastModified(file),
                                )
                            ),
                            partialRequest,
                        )
                    )
                    .get { task ->
                      if (requestedGeneration != analyzeGeneration.get()) {
                        if (IdeLogConfig.shouldLogInfo()) {
                          log.info(
                            "Analyze skipped after compile due to newer request requestedGeneration={} currentGeneration={} file={}",
                            requestedGeneration,
                            analyzeGeneration.get(),
                            file,
                          )
                        }
                        DiagnosticResult.NO_UPDATE
                      } else {
                        doAnalyze(file, task)
                      }
                    }
              } catch (err: Throwable) {
                if (CancelChecker.isCancelled(err)) {
                  if (IdeLogConfig.shouldLogWarn()) {
                    log.warn("Analyze request cancelled")
                  }
                } else {
                  if (IdeLogConfig.shouldLogWarn()) {
                    log.warn("Unable to analyze file", err)
                    log.warn(
                      "Java analyze failure file={} type={} message={} causeType={} causeMessage={} thread={} compilerHash={} currentContextPresent={} synchronizedTaskPresent={} requestedGeneration={} currentGeneration={}",
                      file,
                      err.javaClass.name,
                      err.message,
                      err.cause?.javaClass?.name,
                      err.cause?.message,
                      Thread.currentThread().name,
                      System.identityHashCode(compiler),
                      compiler.compiler.currentContext != null,
                      compiler.getSynchronizedTask() != null,
                      requestedGeneration,
                      analyzeGeneration.get(),
                    )
                  }
                }
                DiagnosticResult.NO_UPDATE
              } finally {
                analyzing.set(false)
              }
    }
  }
}
