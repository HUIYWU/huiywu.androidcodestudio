package com.tom.rv2ide.editor.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.utils.resolveAttr
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.SubscriptionReceipt
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/** Manages hover tooltips showing documentation when cursor hovers over code */
class HoverTooltipManager(private val context: Context, private val editor: IDEEditor) {

  companion object {
    private val log = LoggerFactory.getLogger(HoverTooltipManager::class.java)
    private const val HOVER_DELAY = 800L
    private const val MIN_HOVER_TEXT_CONTRAST = 3.2
  }

  private val handler = Handler(Looper.getMainLooper())
  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  private var currentJob: Job? = null
  private var hoverRunnable: Runnable? = null
  private var selectionReceipt: SubscriptionReceipt<SelectionChangeEvent>? = null
  private var lastHoverLine = -1
  private var lastHoverColumn = -1
  private var isActive = true
  private var requestGeneration = 0L

  /** Initialize hover support */
  fun init() {
    // Listen to cursor/selection changes.
    selectionReceipt =
        editor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ -> handleCursorMove() }
  }

  private fun handleCursorMove() {
    val cursor = editor.cursor
    val line = cursor.leftLine
    val column = cursor.leftColumn

    // If same position, don't restart
    if (line == lastHoverLine && column == lastHoverColumn) {
      return
    }

    lastHoverLine = line
    lastHoverColumn = column

    cancelHover()

    // Schedule hover request after cursor stops moving
    hoverRunnable = Runnable { requestHover(line, column) }
    handler.postDelayed(hoverRunnable!!, HOVER_DELAY)
  }

  private fun cancelHover() {
    hoverRunnable?.let { handler.removeCallbacks(it) }
    hoverRunnable = null
    currentJob?.cancel()
    currentJob = null
    editor.dismissHoverWindow()
  }

  private fun requestHover(line: Int, column: Int) {
    if (!isActive) return
    val file = editor.file ?: return
    val languageServer = editor.languageServer ?: return
    val generation = requestGeneration

    currentJob =
        scope.launch {
          try {
            val cancelChecker =
                object : ICancelChecker {
                  override fun isCancelled(): Boolean {
                    val job = currentJob
                    return job == null || !job.isActive
                  }

                  override fun abortIfCancelled() {
                    if (isCancelled()) {
                      throw CancellationException("Operation cancelled")
                    }
                  }

                  override fun cancel() {
                    currentJob?.cancel()
                  }
                }

            val params =
                DefinitionParams(
                    file = file.toPath(),
                    position = Position(line, column),
                    cancelChecker = cancelChecker,
                )

            val hoverResult = withContext(Dispatchers.IO) { languageServer.hover(params) }

            // Filter out meaningless hover results
            val content = hoverResult.value.trim()
            if (
              isActive &&
                  generation == requestGeneration &&
                  editor.isShown &&
                  content.isNotEmpty() &&
                  content != "Unit" &&
                  !content.equals("unit", ignoreCase = true) &&
                  content.length > 2
            ) {
              withContext(Dispatchers.Main) { displayTooltip(content) }
            }
          } catch (e: Exception) {
            if (e !is CancellationException && IdeLogConfig.shouldLogDebug()) {
              log.debug("Failed to fetch hover info", e)
            }
          }

        }
  }

  private fun displayTooltip(content: String) {
    if (!isActive || !editor.isShown || !editor.canShowHoverWindow()) return

    try {
      editor.hoverWindow.showHover(formatHoverContent(content))
    } catch (e: Exception) {
      log.error("Failed to display hover window", e)
    }
  }

  private fun formatHoverContent(content: String): CharSequence {
    val background = context.resolveAttr(R.attr.colorSurface)
    val defaultTextColor = context.resolveAttr(R.attr.colorOnPrimaryContainer)
    val result = SpannableStringBuilder()

    parseHoverSections(content).forEachIndexed { index, section ->
      if (index > 0) {
        result.append("\n────────\n")
      }

      val start = result.length
      if (section.isCode) {
        result.append(applySyntaxHighlighting(section.text, background, defaultTextColor))
        result.setSpan(
            TypefaceSpan("monospace"),
            start,
            result.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
      } else {
        result.append(formatDocText(section.text))
      }
    }

    return result
  }

  private fun applySyntaxHighlighting(
    text: String,
    backgroundColor: Int,
    defaultTextColor: Int,
  ): CharSequence {
    val builder = SpannableStringBuilder(text)

    val darkTheme = isDarkTheme()
    val keywordColor =
        readableAccentColor(
            if (darkTheme) 0xFF82B1FF.toInt() else 0xFF0D47A1.toInt(),
            backgroundColor,
            defaultTextColor,
        )
    val typeColor =
        readableAccentColor(
            if (darkTheme) 0xFFFFCC80.toInt() else 0xFFE65100.toInt(),
            backgroundColor,
            defaultTextColor,
        )
    val stringColor =
        readableAccentColor(
            if (darkTheme) 0xFFA5D6A7.toInt() else 0xFF1B5E20.toInt(),
            backgroundColor,
            defaultTextColor,
        )
    val commentColor =
        readableMutedColor(
            if (darkTheme) 0xFFB0BEC5.toInt() else 0xFF546E7A.toInt(),
            backgroundColor,
            defaultTextColor,
        )
    val functionColor =
        readableAccentColor(
            if (darkTheme) 0xFF80CBC4.toInt() else 0xFF00695C.toInt(),
            backgroundColor,
            defaultTextColor,
        )
    val annotationColor =
        readableAccentColor(
            if (darkTheme) 0xFFE1BEE7.toInt() else 0xFF6A1B9A.toInt(),
            backgroundColor,
            defaultTextColor,
        )
    val numberColor =
        readableAccentColor(
            if (darkTheme) 0xFFFFAB91.toInt() else 0xFFBF360C.toInt(),
            backgroundColor,
            defaultTextColor,
        )
    val symbolColor =
        readableMutedColor(
            if (darkTheme) 0xFFCFD8DC.toInt() else 0xFF455A64.toInt(),
            backgroundColor,
            defaultTextColor,
        )

    val occupied = BooleanArray(text.length)

    fun applyColor(pattern: Regex, color: Int) {
      pattern.findAll(text).forEach { match ->
        val start = match.range.first
        val endExclusive = match.range.last + 1
        if (start < 0 || endExclusive > text.length || occupied.sliceArray(start until endExclusive).any { it }) {
          return@forEach
        }
        builder.setSpan(
            ForegroundColorSpan(color),
            start,
            endExclusive,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        for (index in start until endExclusive) {
          occupied[index] = true
        }
      }
    }

    // Apply wider/non-code regions first so later generic type matching does not recolor inside them.
    applyColor(Regex("""//.*?$|/\*.*?\*/""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)), commentColor)
    applyColor(Regex("""\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'"""), stringColor)
    applyColor(Regex("""@[A-Za-z_][A-Za-z0-9_]*"""), annotationColor)
    applyColor(
        Regex("""\b(fun|val|var|class|interface|object|return|if|else|for|while|when|in|is|as|private|protected|public|internal|override|suspend|inline|data|sealed|enum|companion|constructor|init|by|where|null|true|false)\b"""),
        keywordColor,
    )
    applyColor(Regex("""\b\d+(?:_\d+)*(?:\.\d+)?(?:[eE][+-]?\d+)?[fFdDlL]?\b"""), numberColor)
    applyColor(Regex("""\b([A-Z][A-Za-z0-9_]*)\b"""), typeColor)
    applyColor(Regex("""\b([a-zA-Z_][A-Za-z0-9_]*)\s*(?=\()"""), functionColor)
    applyColor(Regex("""[<>?:=!,.|&+\-*/%]+"""), symbolColor)

    return builder
  }

  private data class HoverSection(
    val text: String,
    val isCode: Boolean,
  )

  private fun parseHoverSections(raw: String): List<HoverSection> {
    val normalized = raw.replace("\r\n", "\n")
    val result = mutableListOf<HoverSection>()
    val codeBlockRegex = Regex("""```[a-zA-Z0-9_-]*\n(.*?)```""", setOf(RegexOption.DOT_MATCHES_ALL))
    var lastIndex = 0

    codeBlockRegex.findAll(normalized).forEach { match ->
      val before = normalized.substring(lastIndex, match.range.first)
      val code = match.groupValues.getOrElse(1) { "" }
      if (before.isNotBlank()) {
        splitDocSections(before).forEach { result.add(HoverSection(it, false)) }
      }
      if (code.isNotBlank()) {
        result.add(HoverSection(code.trim(), true))
      }
      lastIndex = match.range.last + 1
    }

    val tail = normalized.substring(lastIndex)
    if (tail.isNotBlank()) {
      splitDocSections(tail).forEach { result.add(HoverSection(it, false)) }
    }

    if (result.isEmpty()) {
      val fallback = formatDocText(normalized)
      if (fallback.isNotBlank()) {
        result.add(HoverSection(fallback, false))
      }
    }

    return result
  }

  private fun splitDocSections(text: String): List<String> {
    return text
        .replace(Regex("""\n---+\n"""), "\n\n")
        .split(Regex("""\n\s*\n"""))
        .map { formatDocText(it) }
        .filter { it.isNotBlank() }
  }

  private fun formatDocText(text: String): String {
    return text
        .replace(Regex("""`([^`]+)`"""), "$1")
        .replace(Regex("""\[(.*?)\]\((.*?)\)"""), "$1")
        .replace(Regex("""^#+\s*""", setOf(RegexOption.MULTILINE)), "")
        .trim()
        .take(1000)
  }

  private fun readableMutedColor(candidate: Int, background: Int, defaultTextColor: Int): Int {
    return if (contrastRatio(candidate, background) >= MIN_HOVER_TEXT_CONTRAST) {
      candidate
    } else {
      defaultTextColor
    }
  }

  private fun readableAccentColor(candidate: Int, background: Int, defaultTextColor: Int): Int {
    if (contrastRatio(candidate, background) >= MIN_HOVER_TEXT_CONTRAST) {
      return candidate
    }
    val boosted = shiftColorForContrast(candidate, background)
    if (contrastRatio(boosted, background) >= MIN_HOVER_TEXT_CONTRAST) {
      return boosted
    }
    return defaultTextColor
  }

  private fun shiftColorForContrast(color: Int, background: Int): Int {
    val lighten = !isDarkTheme()
    val factor = if (lighten) 0.18f else -0.18f
    return shiftTowards(color, factor)
  }

  private fun shiftTowards(color: Int, factor: Float): Int {
    val clamped = factor.coerceIn(-1f, 1f)
    fun channel(value: Int): Int {
      val target = if (clamped >= 0f) 255 else 0
      val amount = kotlin.math.abs(clamped)
      return (value + ((target - value) * amount)).toInt().coerceIn(0, 255)
    }
    return Color.argb(255, channel(Color.red(color)), channel(Color.green(color)), channel(Color.blue(color)))
  }

  private fun contrastRatio(foreground: Int, background: Int): Double {
    val foregroundLuminance = relativeLuminance(foreground)
    val backgroundLuminance = relativeLuminance(background)
    val lighter = maxOf(foregroundLuminance, backgroundLuminance)
    val darker = minOf(foregroundLuminance, backgroundLuminance)
    return (lighter + 0.05) / (darker + 0.05)
  }

  private fun relativeLuminance(color: Int): Double {
    fun channel(value: Int): Double {
      val normalized = value / 255.0
      return if (normalized <= 0.03928) {
        normalized / 12.92
      } else {
        Math.pow((normalized + 0.055) / 1.055, 2.4)
      }
    }
    return 0.2126 * channel(Color.red(color)) +
        0.7152 * channel(Color.green(color)) +
        0.0722 * channel(Color.blue(color))
  }

  private fun isDarkTheme(): Boolean {
    val nightModeFlags =
        context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
    return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
  }

  fun setVisible(visible: Boolean) {
    if (isActive == visible) return
    isActive = visible
    requestGeneration++
    if (visible) {
      return
    }
    cancelHover()
  }

  fun destroy() {
    isActive = false
    requestGeneration++
    selectionReceipt?.unsubscribe()
    selectionReceipt = null
    cancelHover()
    scope.cancel()
  }
}

// Extension functions
private const val HOVER_TOOLTIP_TAG = 0x7F0A0002

fun IDEEditor.initHoverTooltips() {
  (getTag(HOVER_TOOLTIP_TAG) as? HoverTooltipManager)?.destroy()
  val tooltipManager = HoverTooltipManager(context, this)
  tooltipManager.init()
  setTag(HOVER_TOOLTIP_TAG, tooltipManager)
}

fun IDEEditor.setHoverTooltipsVisible(visible: Boolean) {
  (getTag(HOVER_TOOLTIP_TAG) as? HoverTooltipManager)?.setVisible(visible)
}

fun IDEEditor.cleanupHoverTooltips() {
  val tooltipManager = getTag(HOVER_TOOLTIP_TAG) as? HoverTooltipManager
  tooltipManager?.destroy()
  setTag(HOVER_TOOLTIP_TAG, null)
}
