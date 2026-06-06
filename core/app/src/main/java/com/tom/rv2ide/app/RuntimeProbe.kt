package com.tom.rv2ide.app

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory

object RuntimeProbe {
  private val log = LoggerFactory.getLogger(RuntimeProbe::class.java)
  private val formatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
  private val counter = AtomicInteger(0)

  @Volatile private var appContext: Context? = null
  @Volatile private var dumpDir: File? = null
  @Volatile private var lastState: String = "idle"
  @Volatile private var lastStateAt: Long = 0L

  fun init(context: Context) {
    appContext = context.applicationContext
    val dir = File(context.filesDir, "diagnostics/runtime_dumps")
    dir.mkdirs()
    dumpDir = dir
    log.info("ACS_RUNTIME_PROBE_INIT dumpDir={}", dir.absolutePath)
    persistState("app.init")
  }

  fun getDumpDirPath(): String = dumpDir?.absolutePath ?: "<uninitialized>"

  fun mark(state: String) {
    lastState = state
    lastStateAt = System.currentTimeMillis()
    persistState(state)
    log.info("ACS_RUNTIME_PROBE_MARK state={} dumpDir={}", state, getDumpDirPath())
  }

  fun dump(reason: String, throwable: Throwable? = null): File? {
    val context = appContext ?: return null
    val dir = dumpDir ?: File(context.filesDir, "diagnostics/runtime_dumps").also {
      it.mkdirs()
      dumpDir = it
    }
    val file = File(dir, "dump_${formatter.format(Date())}_${counter.incrementAndGet()}.txt")
    val runtime = Runtime.getRuntime()
    val usedMem = runtime.totalMemory() - runtime.freeMemory()
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
    val mainThread = Looper.getMainLooper().thread
    val allStacks = Thread.getAllStackTraces()

    file.bufferedWriter().use { w ->
      w.appendLine("reason=$reason")
      w.appendLine("time=${System.currentTimeMillis()}")
      w.appendLine("dumpDir=${dir.absolutePath}")
      w.appendLine("lastState=$lastState")
      w.appendLine("lastStateAt=$lastStateAt")
      w.appendLine("heap.used=$usedMem")
      w.appendLine("heap.total=${runtime.totalMemory()}")
      w.appendLine("heap.max=${runtime.maxMemory()}")
      w.appendLine("debug.pssKb=${Debug.getPss()}")
      w.appendLine("availMem=${memInfo.availMem}")
      w.appendLine("threshold=${memInfo.threshold}")
      w.appendLine("lowMemory=${memInfo.lowMemory}")
      if (throwable != null) {
        w.appendLine("throwable=${throwable::class.java.name}: ${throwable.message}")
        w.appendLine(throwable.stackTraceToString())
      }
      w.appendLine("==== main thread stack (${mainThread.name}) ====")
      mainThread.stackTrace.forEach { w.appendLine(it.toString()) }
      w.appendLine("==== all thread stacks ====")
      allStacks.entries.sortedBy { it.key.name }.forEach { (thread, stack) ->
        w.appendLine("-- thread=${thread.name} id=${thread.id} state=${thread.state} daemon=${thread.isDaemon}")
        stack.forEach { w.appendLine(it.toString()) }
      }
    }
    log.error("ACS_RUNTIME_PROBE_DUMP reason={} file={} lastState={}", reason, file.absolutePath, lastState)
    return file
  }

  private fun persistState(state: String) {
    val context = appContext ?: return
    try {
      val dir = dumpDir ?: File(context.filesDir, "diagnostics/runtime_dumps").also {
        it.mkdirs()
        dumpDir = it
      }
      File(dir, "last_state.txt").writeText(
        "time=${System.currentTimeMillis()}\nstate=$state\ndumpDir=${dir.absolutePath}\n"
      )
    } catch (t: Throwable) {
      log.warn("Failed to persist runtime probe state", t)
    }
  }
}

class AnrWatchdog(
  private val timeoutMs: Long = 8000L,
  private val tickHandler: Handler = Handler(Looper.getMainLooper()),
) : Thread("acs-anr-watchdog") {
  @Volatile private var tick = 0

  override fun run() {
    while (!isInterrupted) {
      val before = tick
      tickHandler.post { tick++ }
      try {
        sleep(timeoutMs)
      } catch (_: InterruptedException) {
        interrupt()
        return
      }
      if (tick == before) {
        RuntimeProbe.dump("anr-watchdog timeout=${timeoutMs}ms")
      }
    }
  }
}
