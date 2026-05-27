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

package com.tom.rv2ide.fragments.output

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.EditText
import com.blankj.utilcode.util.ThreadUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.R
import com.tom.rv2ide.databinding.FragmentLogViewerBinding
import com.tom.rv2ide.syntax.colorschemes.SchemeAndroidIDE
import com.tom.rv2ide.fragments.EmptyStateFragment
import com.tom.rv2ide.preferences.internal.DevOpsPreferences
import com.tom.rv2ide.utils.jetbrainsMono
import io.github.mohammedbaqernull.logger.model.LogEntry
import io.github.mohammedbaqernull.logger.service.LogReceiverService
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.widget.style.CursorAnimator
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min
import org.slf4j.LoggerFactory

/**
 * Fragment to show application logs from LogReceiverService.
 *
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class AppLogFragment :
    EmptyStateFragment<FragmentLogViewerBinding>(R.layout.fragment_log_viewer, FragmentLogViewerBinding::bind),
    ShareableOutputFragment {

    companion object {
        private val log = LoggerFactory.getLogger(AppLogFragment::class.java)

        const val MAX_CHUNK_SIZE = 10000
        const val LOG_FREQUENCY = 50L
        const val LOG_DELAY = 100L
        const val TRIM_ON_LINE_COUNT = 5000
        const val MAX_LINE_COUNT = TRIM_ON_LINE_COUNT - 300

        private const val LOGWIRE_HEADER = """
╔═══════════════════════════════════════════════════════════╗
║                    Powered by LogWire                     ║
║               Real-time Log Monitoring Tool               ║
║                                                           ║
║   Author: Mohammed-baqer-null                             ║
║   GitHub: https://github.com/Mohammed-baqer-null          ║
╚═══════════════════════════════════════════════════════════╝

"""

        fun newInstance() = AppLogFragment()
    }

    private object LogTextHighlighter {
        private val logLevelPattern = Regex("""^\S+\s+\[([A-Z])\]\s+([^:]+):\s?(.*)$""")
        private val stackTracePattern = Regex("""^\s*at\s+[\w.$]+\(.*\)$""")
        private val causedByPattern = Regex("""^\s*Caused by:.*$""")
        private val exceptionPattern = Regex(""".*(?:Exception|Error|FATAL EXCEPTION|SIGSEGV|ANR|OutOfMemoryError|NullPointerException|ClassCastException|IllegalStateException|IllegalArgumentException|failed|timeout).*""", RegexOption.IGNORE_CASE)

        fun build(text: String, colors: LogColors): CharSequence {
            if (text.isEmpty()) return text

            val builder = SpannableStringBuilder(text)
            var lineStart = 0
            while (lineStart < builder.length) {
                val lineEnd = text.indexOf('\n', lineStart).let { if (it >= 0) it else builder.length }
                highlightLine(builder, text.substring(lineStart, lineEnd), lineStart, lineEnd, colors)
                lineStart = if (lineEnd < builder.length) lineEnd + 1 else builder.length
            }
            return builder
        }

        private fun highlightLine(
            builder: SpannableStringBuilder,
            line: String,
            start: Int,
            end: Int,
            colors: LogColors,
        ) {
            if (start >= end) return

            val stackMatch = stackTracePattern.matches(line)
            val causedByMatch = causedByPattern.matches(line)
            val exceptionMatch = exceptionPattern.matches(line)

            when {
                stackMatch -> {
                    applyColor(builder, colors.stackTrace, start, end)
                    return
                }
                causedByMatch || exceptionMatch -> {
                    applyColor(builder, colors.error, start, end)
                    applyStyle(builder, Typeface.BOLD, start, end)
                }
            }

            val match = logLevelPattern.find(line) ?: return
            val level = match.groupValues[1].firstOrNull() ?: return
            val tagStart = start + match.groups[2]!!.range.first
            val tagEnd = start + match.groups[2]!!.range.last + 1
            val messageStart = start + match.groups[3]!!.range.first
            val messageEnd = start + match.groups[3]!!.range.last + 1

            val levelColor = when (level) {
                'E', 'A', 'F' -> colors.error
                'W' -> colors.warn
                'I' -> colors.info
                'D' -> colors.debug
                'V' -> colors.verbose
                else -> colors.defaultText
            }

            applyColor(builder, levelColor, start, end)
            applyStyle(builder, Typeface.BOLD, start, tagEnd.coerceAtMost(end))
            applyColor(builder, colors.tag, tagStart, tagEnd.coerceAtMost(end))

            if (messageStart < messageEnd && exceptionPattern.matches(line.substring(match.groups[3]!!.range))) {
                applyColor(builder, colors.error, messageStart, messageEnd.coerceAtMost(end))
                applyStyle(builder, Typeface.BOLD, messageStart, messageEnd.coerceAtMost(end))
            }
        }

        private fun applyColor(builder: SpannableStringBuilder, color: Int, start: Int, end: Int) {
            if (start >= end) return
            builder.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        private fun applyStyle(builder: SpannableStringBuilder, style: Int, start: Int, end: Int) {
            if (start >= end) return
            builder.setSpan(StyleSpan(style), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private data class LogColors(
        val defaultText: Int,
        val verbose: Int,
        val debug: Int,
        val info: Int,
        val warn: Int,
        val error: Int,
        val tag: Int,
        val stackTrace: Int,
    )

    private var logService: LogReceiverService? = null
    private var isBound = false
    private var filterSystemLogs = true
    private var tagFilter: String? = null
    private var searchQuery: String? = null
    private var isUsbDebuggingEnabled = false

    private val allLogs = mutableListOf<LogEntry>()

    private var lastLog = -1L
    private val cacheLock = ReentrantLock()
    private val cache = StringBuilder()
    private var cacheLineTrack = ArrayBlockingQueue<Int>(MAX_LINE_COUNT, true)
    private val isTrimming = AtomicBoolean(false)

    private val logHandler = Handler(Looper.getMainLooper())
    private val logRunnable =
        object : Runnable {
            override fun run() {
                cacheLock.withLock {
                    if (cacheLineTrack.size == MAX_LINE_COUNT) {
                        cache.delete(0, cacheLineTrack.poll()!!)
                    }

                    cacheLineTrack.clear()

                    if (cache.length < MAX_CHUNK_SIZE) {
                        append(cache)
                        cache.clear()
                    } else {
                        val length = min(cache.length, MAX_CHUNK_SIZE)
                        append(cache.subSequence(0, length))
                        cache.delete(0, length)
                    }

                    if (cache.isNotEmpty()) {
                        logHandler.removeCallbacks(this)
                        logHandler.postDelayed(this, LOG_DELAY)
                    } else {
                        trimLinesAtStart()
                    }
                }
            }
        }

    private val systemTags = setOf(
        "VRI", "InputMethodManager", "InputEventReceiver", "ImeFocusController",
        "SurfaceControl", "BufferQueueProducer", "BufferQueueConsumer",
        "RenderService", "libEGL", "HwViewRootImpl", "RmeSchedManager",
        "RtgSchedEvent", "RtgSchedIpcFile", "RtgSched", "PhoneWindow",
        "FullScreenUtils", "DecorView", "HWUI", "skia", "AwareBitmapCacher",
        "Resource", "ProfileInstaller", "ZrHung", "libc", "dalvikvm",
        "art", "Choreographer", "LogWire"
    )

    private val logListener = object : LogReceiverService.LogListener {
        override fun onLogReceived(log: LogEntry) {
            if (!isUsbDebuggingEnabled) {
                return
            }

            activity?.runOnUiThread {
                allLogs.add(log)
                if (shouldDisplayLog(log)) {
                    appendLogToEditor(log)
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            logService = LogReceiverService.getInstance()
            isBound = true
            logService?.addListener(logListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            logService?.removeListener(logListener)
            logService = null
            isBound = false
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupEditor()
        setupMenu()
        checkUsbDebugging()

        if (DevOpsPreferences.logsenderEnabled) {
          if (isUsbDebuggingEnabled) {
              showLogWireHeader()
              bindToLogService()
          }
        } else {
          showLogWireDisabledMessage()
        }

    }

    private fun setupMenu() {
        binding.btnClear.setOnClickListener {
            clearOutput()
            if (isUsbDebuggingEnabled) {
                showLogWireHeader()
            }
        }

        binding.btnFilterSystem.setOnClickListener {
            toggleSystemLogsFilter()
            binding.btnFilterSystem.text = if (filterSystemLogs) {
                getString(R.string.action_show_system_logs)
            } else {
                getString(R.string.action_hide_system_logs)
            }
        }

        binding.btnFilterTag.setOnClickListener {
            showTagFilterDialog()
        }

        binding.btnSearch.setOnClickListener {
            showSearchDialog()
        }
    }

    private fun setupEditor() {
        val editor = this.binding.logEditor
        editor.props.autoIndent = false
        editor.isEditable = false
        editor.dividerWidth = 0f
        editor.isWordwrap = false
        editor.isUndoEnabled = false
        editor.typefaceLineNumber = jetbrainsMono()
        editor.setTextSize(8f)
        editor.typefaceText = jetbrainsMono()
        editor.inputType = InputType.TYPE_NULL
        editor.setEditorLanguage(EmptyLanguage())
        editor.setColorScheme(SchemeAndroidIDE.newInstance(requireContext()))
        editor.cursorAnimator =
            object : CursorAnimator {
                override fun markStartPos() {}
                override fun markEndPos() {}
                override fun start() {}
                override fun cancel() {}
                override fun isRunning(): Boolean = false
                override fun animatedX(): Float = 0f
                override fun animatedY(): Float = 0f
                override fun animatedLineHeight(): Float = 0f
                override fun animatedLineBottom(): Float = 0f
            }
    }

    private fun append(chars: CharSequence?) {
        chars?.let {
            ThreadUtils.runOnUiThread {
                val editor = _binding?.logEditor ?: return@runOnUiThread
                val existingText = editor.text?.toString().orEmpty()
                val updatedText = existingText + chars.toString()
                editor.setText(highlightLogText(updatedText))
                emptyStateViewModel.isEmpty.value = updatedText.isEmpty()
            }
        }
    }

    private fun highlightLogText(text: String): CharSequence {
        return LogTextHighlighter.build(text, resolveLogColors())
    }

    private fun resolveLogColors(): LogColors {
        return LogColors(
            defaultText = Color.parseColor("#FFCCCCCC"),
            verbose = Color.parseColor("#FF9E9E9E"),
            debug = Color.parseColor("#FF66BB6A"),
            info = Color.parseColor("#FF42A5F5"),
            warn = Color.parseColor("#FFFFB300"),
            error = Color.parseColor("#FFEF5350"),
            tag = Color.parseColor("#FFBA68C8"),
            stackTrace = Color.parseColor("#FFFF8A65"),
        )
    }

    private fun checkUsbDebugging() {
        isUsbDebuggingEnabled = try {
            Settings.Global.getInt(
                requireContext().contentResolver,
                Settings.Global.ADB_ENABLED,
                0
            ) == 1
        } catch (e: Exception) {
            log.error("Failed to check USB debugging status", e)
            false
        }

        if (!isUsbDebuggingEnabled) {
            showUsbDebuggingDisabledMessage()
        }
    }

    private fun bindToLogService() {
        val intent = Intent(requireContext(), LogReceiverService::class.java)
        requireContext().startService(intent)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun shouldDisplayLog(log: LogEntry): Boolean {
        if (filterSystemLogs && isSystemLog(log)) {
            return false
        }

        if (tagFilter != null && !log.tag.equals(tagFilter, ignoreCase = true)) {
            return false
        }

        if (searchQuery != null) {
            val query = searchQuery!!.lowercase()
            return log.tag.lowercase().contains(query) ||
                   log.message.lowercase().contains(query)
        }

        return true
    }

    private fun isSystemLog(log: LogEntry): Boolean {
        if (systemTags.any { log.tag.startsWith(it) }) return true
        if (log.tag.startsWith(".")) return true

        val message = log.message
        if (message.contains("type=1400 audit")) return true
        if (message.contains("RCS is disable")) return true
        if (message.contains("Compiler allocated")) return true
        if (message.contains("denied")) return true
        if (message.contains("Access denied")) return true

        return false
    }

    fun appendLogToEditor(log: LogEntry) {
        if (!isUsbDebuggingEnabled) {
            return
        }

        val logLine = formatLogEntry(log)
        appendLine(logLine)
    }

    private fun appendLine(line: String) {
        var lineStr = line
        if (!lineStr.endsWith("\n")) {
            lineStr += "\n"
        }

        if (
            isTrimming.get() ||
            cache.isNotEmpty() ||
            System.currentTimeMillis() - lastLog <= LOG_FREQUENCY
        ) {
            cacheLock.withLock {
                logHandler.removeCallbacks(logRunnable)

                cache.append(lineStr)
                logHandler.postDelayed(logRunnable, LOG_DELAY)

                lastLog = System.currentTimeMillis()

                val length = cache.length + 1
                if (!cacheLineTrack.offer(length)) {
                    cacheLineTrack.poll()
                    cacheLineTrack.offer(length)
                }
            }
            return
        }

        lastLog = System.currentTimeMillis()

        append(lineStr)
        trimLinesAtStart()
    }

    private fun trimLinesAtStart() {
        if (isTrimming.get()) {
            return
        }

        ThreadUtils.runOnUiThread {
            val editor = _binding?.logEditor ?: return@runOnUiThread
            val text = editor.text?.toString().orEmpty()
            val lines = text.lines()
            if (lines.size <= TRIM_ON_LINE_COUNT) {
                isTrimming.set(false)
                return@runOnUiThread
            }

            isTrimming.set(true)
            val trimmed = lines.takeLast(MAX_LINE_COUNT).joinToString("\n")
            editor.setText(highlightLogText(trimmed))
            isTrimming.set(false)
        }
    }

    private fun formatLogEntry(log: LogEntry): String {
        return "${log.getFormattedTime()} [${log.level.label}] ${log.tag}: ${log.message}"
    }

    private fun refreshDisplay() {
        logHandler.removeCallbacks(logRunnable)
        cacheLock.withLock {
            cache.clear()
            cacheLineTrack.clear()
        }

        ThreadUtils.runOnUiThread {
            if (isUsbDebuggingEnabled) {
                val contentBuilder = StringBuilder(LOGWIRE_HEADER)

                for (log in allLogs) {
                    if (shouldDisplayLog(log)) {
                        contentBuilder.append(formatLogEntry(log))
                        if (!contentBuilder.endsWith("\n")) {
                            contentBuilder.append("\n")
                        }
                    }
                }

                val content = contentBuilder.toString()
                _binding?.logEditor?.setText(highlightLogText(content))
                emptyStateViewModel.isEmpty.value = content.isEmpty()
            } else {
                showUsbDebuggingDisabledMessage()
            }
        }
    }

    private fun toggleSystemLogsFilter() {
        filterSystemLogs = !filterSystemLogs
        refreshDisplay()
    }

    private fun showLogWireHeader() {
        _binding?.logEditor?.setText(highlightLogText(LOGWIRE_HEADER))
        emptyStateViewModel.isEmpty.value = false
    }

    private fun showUsbDebuggingDisabledMessage() {
        val message = """
    ╔═══════════════════════════════════════════════════════════╗
    ║                                                           ║
    ║                  USB DEBUGGING DISABLED                   ║
    ║                                                           ║
    ║   LogWire requires USB debugging to be enabled in order   ║
    ║   to read your app logs.                                  ║
    ║                                                           ║
    ║   To enable USB debugging:                                ║
    ║   1. Go to Settings > About Phone                         ║
    ║   2. Tap "Build Number" 7 times to enable Developer Mode  ║
    ║   3. Go to Settings > Developer Options                   ║
    ║   4. Enable "USB Debugging"                               ║
    ║   5. Close and reopen your project                        ║
    ║                                                           ║
    ╚═══════════════════════════════════════════════════════════╝
    """
        _binding?.logEditor?.setText(highlightLogText(message))
        emptyStateViewModel.isEmpty.value = false
    }

    private fun showLogWireDisabledMessage() {
        val message = """
    ╔═══════════════════════════════════════════════════════════╗
    ║                  LogWire DISABLED                         ║
    ║              LogWire plugin is disabled                   ║
    ║   To enable :                                             ║
    ║   1. Go to Preferences > Developer options                ║
    ║   2. Toggle the Enable LogWire switch                     ║
    ║                                                           ║
    ╚═══════════════════════════════════════════════════════════╝
    """
        _binding?.logEditor?.setText(highlightLogText(message))
        emptyStateViewModel.isEmpty.value = false
    }

    override fun clearOutput() {
        logHandler.removeCallbacks(logRunnable)

        cacheLock.withLock {
            cache.clear()
            cacheLineTrack.clear()
        }

        allLogs.clear()

        ThreadUtils.runOnUiThread {
            _binding?.logEditor?.setText("")
            emptyStateViewModel.isEmpty.value = true
        }
    }

    private fun showTagFilterDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.hint_tag_filter)
            setText(tagFilter ?: "")
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.title_filter_by_tag)
            .setView(input)
            .setPositiveButton(R.string.action_apply) { _, _ ->
                val tag = input.text.toString().trim()
                tagFilter = if (tag.isEmpty()) null else tag
                refreshDisplay()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .setNeutralButton(R.string.action_clear_filter) { _, _ ->
                tagFilter = null
                refreshDisplay()
            }
            .show()
    }

    private fun showSearchDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.hint_search_logs)
            setText(searchQuery ?: "")
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.title_search_logs)
            .setView(input)
            .setPositiveButton(R.string.action_search) { _, _ ->
                val query = input.text.toString().trim()
                searchQuery = if (query.isEmpty()) null else query
                refreshDisplay()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .setNeutralButton(R.string.action_clear_search) { _, _ ->
                searchQuery = null
                refreshDisplay()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding?.logEditor?.release()
        logHandler.removeCallbacks(logRunnable)
        if (isBound) {
            logService?.removeListener(logListener)
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun getContent(): String {
        return this._binding?.logEditor?.text?.toString() ?: ""
    }

    override fun getFilename(): String = "app_logs"
}
