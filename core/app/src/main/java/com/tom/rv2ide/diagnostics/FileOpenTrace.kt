package com.tom.rv2ide.diagnostics

import android.view.Choreographer
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostic-only tracing for the file-open pipeline.
 *
 * The trace is intentionally lightweight and can be left in source while the issue is diagnosed.
 * It correlates file-tree clicks, editor creation/content readiness, UI attach, tab selection,
 * LiveData observers, main-looper slow messages and frame jank with the same file path.
 */
object FileOpenTrace {
  private const val TAG = "FileOpenTrace"
  private const val FRAME_TAG = "FileOpenFrame"
  private const val LOOPER_TAG = "FileOpenLooper"
  private const val ENABLED = true
  private const val SLOW_MESSAGE_THRESHOLD_MS = 8.0
  private const val JANK_FRAME_THRESHOLD_MS = 24.0
  private const val FRAME_MONITOR_DURATION_MS = 800L

  private val nextId = AtomicLong(1)
  private val traces = ConcurrentHashMap<String, Trace>()
  private val looperInstalled = AtomicBoolean(false)

  @Volatile private var activeFrameTrace: Trace? = null
  @Volatile private var frameMonitorUntilNs: Long = 0L
  @Volatile private var lastFrameNs: Long = 0L
  @Volatile private var currentMainMessage: String? = null
  @Volatile private var currentMainMessageStartNs: Long = 0L

  data class Trace(
    val id: Long,
    val filePath: String,
    val fileName: String,
    val startNs: Long = System.nanoTime(),
    @Volatile var lastNs: Long = startNs,
  )

  fun begin(file: File, stage: String): Trace? {
    if (!ENABLED) return null
    installMainLooperMonitor()
    val key = key(file)
    val trace = Trace(nextId.getAndIncrement(), key, file.name)
    traces[key] = trace
    activeFrameTrace = trace
    frameMonitorUntilNs = System.nanoTime() + FRAME_MONITOR_DURATION_MS * 1_000_000L
    lastFrameNs = 0L
    Choreographer.getInstance().postFrameCallback(frameCallback)
    mark(file, stage)
    return trace
  }

  fun mark(file: File?, stage: String) {
    if (!ENABLED || file == null) return
    mark(find(file), stage)
  }

  fun mark(trace: Trace?, stage: String) {
    if (!ENABLED || trace == null) return
    val now = System.nanoTime()
    val totalMs = (now - trace.startNs) / 1_000_000.0
    val deltaMs = (now - trace.lastNs) / 1_000_000.0
    trace.lastNs = now
    Log.i(
      TAG,
      "#${trace.id} +${fmt(totalMs)}ms Δ${fmt(deltaMs)}ms stage=$stage file=${trace.fileName} thread=${Thread.currentThread().name}",
    )
  }

  fun end(file: File?, stage: String = "end") {
    if (!ENABLED || file == null) return
    val trace = find(file)
    mark(trace, stage)
    if (trace != null) {
      // Keep the trace briefly available for deferred UI callbacks sharing the same file.
      traces.remove(trace.filePath)
    }
  }

  fun find(file: File?): Trace? {
    if (!ENABLED || file == null) return null
    return traces[key(file)]
  }

  fun installMainLooperMonitor() {
    if (!ENABLED || !looperInstalled.compareAndSet(false, true)) return
    Looper.getMainLooper().setMessageLogging { message ->
      val now = System.nanoTime()
      if (message.startsWith(">>>>>")) {
        currentMainMessage = message
        currentMainMessageStartNs = now
      } else if (message.startsWith("<<<<<")) {
        val start = currentMainMessageStartNs
        if (start > 0L) {
          val costMs = (now - start) / 1_000_000.0
          if (costMs >= SLOW_MESSAGE_THRESHOLD_MS) {
            val trace = activeFrameTrace
            val tracePrefix = if (trace != null) "#${trace.id} file=${trace.fileName} " else ""
            Log.w(LOOPER_TAG, "${tracePrefix}mainMessage=${fmt(costMs)}ms msg=${currentMainMessage}")
          }
        }
        currentMainMessage = null
        currentMainMessageStartNs = 0L
      }
    }
  }

  private val frameCallback =
    object : Choreographer.FrameCallback {
      override fun doFrame(frameTimeNanos: Long) {
        val trace = activeFrameTrace
        if (trace != null && lastFrameNs != 0L) {
          val frameMs = (frameTimeNanos - lastFrameNs) / 1_000_000.0
          if (frameMs >= JANK_FRAME_THRESHOLD_MS) {
            val totalMs = (frameTimeNanos - trace.startNs) / 1_000_000.0
            Log.w(FRAME_TAG, "#${trace.id} +${fmt(totalMs)}ms frame=${fmt(frameMs)}ms file=${trace.fileName}")
          }
        }
        lastFrameNs = frameTimeNanos
        if (System.nanoTime() < frameMonitorUntilNs) {
          Choreographer.getInstance().postFrameCallback(this)
        } else if (activeFrameTrace === trace) {
          activeFrameTrace = null
        }
      }
    }

  private fun key(file: File): String = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

  private fun fmt(value: Double): String = String.format(Locale.US, "%.3f", value)
}
