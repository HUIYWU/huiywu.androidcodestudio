package com.tom.rv2ide.fragments.output

import android.os.Bundle
import com.tom.rv2ide.syntax.colorschemes.SchemeAndroidIDE
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference

class LogEditorLanguage : EmptyLanguage() {

  private val analyzer = LogAnalyzeManager()

  override fun getAnalyzeManager(): AnalyzeManager {
    return analyzer
  }

  override fun getInterruptionLevel(): Int = INTERRUPTION_LEVEL_STRONG

  override fun requireAutoComplete(
      content: ContentReference,
      position: CharPosition,
      publisher: CompletionPublisher,
      extraArguments: Bundle,
  ) {
    // no-op
  }

  override fun destroy() {
    analyzer.destroy()
  }
}

private class LogAnalyzeManager : SimpleAnalyzeManager<Unit>() {

  override fun analyze(text: StringBuilder, delegate: Delegate<Unit>): Styles {
    val builder = MappedSpans.Builder()
    var lineIndex = 0
    var lineStart = 0
    var index = 0
    val length = text.length

    while (index < length) {
      if (delegate.isCancelled) {
        break
      }
      if (text[index] == '\n') {
        addLineSpans(builder, lineIndex, text, lineStart, index)
        lineIndex++
        lineStart = index + 1
      }
      index++
    }

    if (!delegate.isCancelled) {
      addLineSpans(builder, lineIndex, text, lineStart, length)
    }

    builder.determine(lineIndex.coerceAtLeast(0))
    return Styles(builder.build()).apply { finishBuilding() }
  }

  private fun addLineSpans(
      builder: MappedSpans.Builder,
      lineIndex: Int,
      source: StringBuilder,
      start: Int,
      endExclusive: Int,
  ) {
    addSpan(builder, lineIndex, 0, SchemeAndroidIDE.TEXT_NORMAL)

    val end = if (endExclusive > start && source[endExclusive - 1] == '\r') endExclusive - 1 else endExclusive
    val text = source.substring(start, end)

    val match = LOG_LINE.matchEntire(text)
    if (match != null) {
      addStructuredLogLineSpans(builder, lineIndex, text, match)
      return
    }

    when {
      STACK_TRACE.matches(text) -> addSpan(builder, lineIndex, 0, SchemeAndroidIDE.COMMENT)
      CAUSED_BY.matches(text) || EXCEPTION_HINT.containsMatchIn(text) ->
          addSpan(builder, lineIndex, 0, SchemeAndroidIDE.LOG_TEXT_ERROR)
    }
  }

  private fun addStructuredLogLineSpans(
      builder: MappedSpans.Builder,
      lineIndex: Int,
      text: String,
      match: MatchResult,
  ) {
    val levelRange = match.groups[2]?.range ?: return
    val threadRange = match.groups[3]?.range
    val tagRange = match.groups[4]?.range
    val messageRange = match.groups[5]?.range
    val level = match.groupValues[2]

    val levelStyle = priorityStyle(level)
    builder.add(lineIndex, SpanFactory.obtain(levelRange.first, levelStyle))

    val levelEnd = levelRange.last + 1
    if (levelEnd < text.length) {
      addSpan(builder, lineIndex, levelEnd, SchemeAndroidIDE.TEXT_NORMAL)
    }

    if (threadRange != null) {
      addSpan(builder, lineIndex, threadRange.first, SchemeAndroidIDE.COMMENT)
      val threadEnd = threadRange.last + 1
      if (threadEnd < text.length) {
        addSpan(builder, lineIndex, threadEnd, SchemeAndroidIDE.TEXT_NORMAL)
      }
    }

    if (tagRange != null) {
      addSpan(builder, lineIndex, tagRange.first, SchemeAndroidIDE.ANNOTATION)
      val tagEnd = tagRange.last + 1
      if (tagEnd < text.length) {
        addSpan(builder, lineIndex, tagEnd, SchemeAndroidIDE.TEXT_NORMAL)
      }
    }

    if (messageRange != null) {
      val message = match.groupValues[5]
      val messageColor = if (EXCEPTION_HINT.containsMatchIn(message)) {
        SchemeAndroidIDE.LOG_TEXT_ERROR
      } else {
        textColorForLevel(level)
      }
      addSpan(builder, lineIndex, messageRange.first, messageColor)
    }
  }

  private fun addSpan(builder: MappedSpans.Builder, lineIndex: Int, column: Int, colorId: Int) {
    builder.add(lineIndex, SpanFactory.obtain(column, TextStyle.makeStyle(colorId)))
  }

  private fun priorityStyle(level: String): Long {
    return when (level) {
      "ERROR", "FATAL" ->
          TextStyle.makeStyle(
              SchemeAndroidIDE.LOG_PRIORITY_FG_ERROR,
              SchemeAndroidIDE.LOG_PRIORITY_BG_ERROR,
              true,
              false,
              false,
          )
      "WARN" ->
          TextStyle.makeStyle(
              SchemeAndroidIDE.LOG_PRIORITY_FG_WARNING,
              SchemeAndroidIDE.LOG_PRIORITY_BG_WARNING,
              true,
              false,
              false,
          )
      "INFO" ->
          TextStyle.makeStyle(
              SchemeAndroidIDE.LOG_PRIORITY_FG_INFO,
              SchemeAndroidIDE.LOG_PRIORITY_BG_INFO,
              true,
              false,
              false,
          )
      "DEBUG" ->
          TextStyle.makeStyle(
              SchemeAndroidIDE.LOG_PRIORITY_FG_DEBUG,
              SchemeAndroidIDE.LOG_PRIORITY_BG_DEBUG,
              true,
              false,
              false,
          )
      "VERBOSE", "TRACE" ->
          TextStyle.makeStyle(
              SchemeAndroidIDE.LOG_PRIORITY_FG_VERBOSE,
              SchemeAndroidIDE.LOG_PRIORITY_BG_VERBOSE,
              true,
              false,
              false,
          )
      else -> TextStyle.makeStyle(SchemeAndroidIDE.TEXT_NORMAL)
    }
  }

  private fun textColorForLevel(level: String): Int {
    return when (level) {
      "ERROR", "FATAL" -> SchemeAndroidIDE.LOG_TEXT_ERROR
      "WARN" -> SchemeAndroidIDE.LOG_TEXT_WARNING
      "INFO" -> SchemeAndroidIDE.LOG_TEXT_INFO
      "DEBUG" -> SchemeAndroidIDE.TYPE_NAME
      "VERBOSE", "TRACE" -> SchemeAndroidIDE.LOG_TEXT_VERBOSE
      else -> SchemeAndroidIDE.TEXT_NORMAL
    }
  }

  companion object {
    // Example:
    // 26-05 12:56:13.600  INFO [main] BaseEditorActivity:  Connected...
    private val LOG_LINE =
        Regex("""^(\S+\s+\S+\s+)(TRACE|DEBUG|INFO|WARN|ERROR|FATAL|VERBOSE)\s+(\[[^\]]+\]\s+)([^:]+):\s?(.*)$""")
    private val STACK_TRACE = Regex("""^\s*at\s+[\w.$]+\(.*\)$""")
    private val CAUSED_BY = Regex("""^\s*Caused by:.*$""")

    // Deliberately avoids a bare "error" token so strings like
    // "clangd-error-reader" or "error_prone_annotations" are not painted red.
    private val EXCEPTION_HINT =
        Regex(
            """\b(?:Exception|FATAL EXCEPTION|SIGSEGV|ANR|OutOfMemoryError|NullPointerException|ClassCastException|IllegalStateException|IllegalArgumentException|failed|failure|timeout)\b""",
            RegexOption.IGNORE_CASE,
        )
  }
}

