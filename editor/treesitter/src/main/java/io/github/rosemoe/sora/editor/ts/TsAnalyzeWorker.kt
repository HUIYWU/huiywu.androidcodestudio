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

package io.github.rosemoe.sora.editor.ts

import android.os.SystemClock
import com.itsaky.androidide.treesitter.TSInputEdit
import com.itsaky.androidide.treesitter.TSQueryCursor
import com.itsaky.androidide.treesitter.TSTree
import com.itsaky.androidide.treesitter.api.TreeSitterInputEdit
import com.itsaky.androidide.treesitter.api.TreeSitterQueryCapture
import com.itsaky.androidide.treesitter.api.safeExecQueryCursor
import com.itsaky.androidide.treesitter.string.UTF16String
import io.github.rosemoe.sora.data.ObjectAllocator
import io.github.rosemoe.sora.editor.ts.spans.TsSpanFactory
import io.github.rosemoe.sora.lang.analysis.StyleReceiver
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.text.ContentReference
import java.util.concurrent.CancellationException
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import org.slf4j.LoggerFactory

/** @author Akash Yadav */
class TsAnalyzeWorker(
    private val analyzer: TsAnalyzeManager,
    private val languageSpec: TsLanguageSpec,
    private val theme: TsTheme,
    private val styles: Styles,
    private val reference: ContentReference,
    private val spanFactory: TsSpanFactory,
) {

  companion object {

    private val log = LoggerFactory.getLogger(TsAnalyzeWorker::class.java)
    private const val MIN_STYLE_UPDATE_INTERVAL_MS = 16L
    private const val MAX_MOD_BATCH = 8
  }

  var stylesReceiver: StyleReceiver? = null

  @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
  private val analyzerContext = newSingleThreadContext("TsAnalyzeWorkerContext")

  private val analyzerScope = CoroutineScope(analyzerContext)
  private val messageChannel = LinkedBlockingQueue<Message<*>>()
  private var analyzerJob: Job? = null
  private var lastStylesUpdateTime = 0L

  private var isInitialized = false
  private var isDestroyed = false

  val document = TsTextDocument(languageSpec.language)

  internal val tree: TSTree?
    get() = document.tree

  internal val text: UTF16String
    get() = document.text

  internal fun init(init: Init) {
    if (isDestroyed) {
      log.warn("Received Init after TsAnalyzeWorker has been destroyed. Ignoring...")
      return
    }

    messageChannel.offer(init)
  }

  internal fun onMod(mod: Mod) {
    if (isDestroyed) {
      log.warn("Received Mod after TsAnalyzeWorker has been destroyed. Ignoring...")
      return
    }

    messageChannel.offer(mod)
  }

  fun stop() {
    log.debug("Stopping TsAnalyzeWorker...")
    isDestroyed = true

    document.requestCancellationAndWaitIfParsing()

    analyzerContext.close()
    messageChannel.clear()
    analyzerJob?.cancel(CancellationException("Requested to be stopped"))
    analyzerScope.cancel(CancellationException("Requested to be stopped"))
    document.close()
  }

  fun start() {
    check(!isDestroyed) { "TsAnalyeWorker has already been destroyed" }

    analyzerJob =
        analyzerScope
            .launch {
              while (!isDestroyed && isActive) {
                processNextMessage()
              }
            }
            .also { job ->
              job.invokeOnCompletion { error ->
                if (error != null && error !is CancellationException) {
                  log.error("Analyzer job failed", error)
                } else {
                  log.info("Analyzer job completed")
                }
              }
            }
  }

  private fun processNextMessage() {
    val message = messageChannel.take()
    if (isDestroyed) {
      return
    }

    try {
      when (message) {
        is Init -> doInit(message)
        is Mod -> processMods(message)
      }
    } catch (err: Throwable) {
      val langName = languageSpec.language.name
      val msgType = message.javaClass.simpleName
      val msgTypeSuffix =
          if (message is Mod) {
            "[start=${message.data.start}, end=${message.data.end}, type=${if (message.data.changedText == null) "delete" else "insert"}]"
          } else ""
      val pendingMsgs = messageChannel.size
      log.error(
          "AnalyzeWorker[lang={}, message={}{}], pendingMsgs={}] crashed",
          langName,
          msgType,
          msgTypeSuffix,
          pendingMsgs,
          err,
      )
    }
  }

  private fun doInit(init: Init) {
    document.requestCancellationAndWaitIfParsing()

    check(!isInitialized) { "'Init' must be the first message to TsAnalyzeWorker" }

    document.doInit(init.data)
    document.reparse()
    updateStyles()

    isInitialized = true
  }

  private fun processMods(initial: Mod) {

    check(isInitialized) { "'Init' must be the first message to TsAnalyzeWorker" }

    val mods = mutableListOf(initial)
    while (mods.size < MAX_MOD_BATCH) {
      val next = messageChannel.peek() ?: break
      if (next !is Mod) {
        break
      }
      messageChannel.poll()
      mods.add(next)
    }

    applyMods(mods)
  }

  private fun applyMods(mods: List<Mod>) {
    val workingTree = tree!!

    try {
      mods.forEach { mod ->
        val textMod = mod.data
        val edit = textMod.edit
        val textByteLength = text.length shl 1
        val isInsert = textMod.changedText != null
        val invalidEdit =
            if (isInsert) {
              textMod.start < 0 || textMod.start > text.length || edit.startByte < 0 ||
                  edit.startByte > textByteLength || edit.oldEndByte < edit.startByte ||
                  edit.oldEndByte > textByteLength
            } else {
              edit.startByte < 0 || edit.startByte > textByteLength ||
                  edit.oldEndByte < edit.startByte || edit.oldEndByte > textByteLength
            }

        if (invalidEdit) {
          throw IllegalStateException(
              "Invalid tree-sitter mod: start=${textMod.start}, end=${textMod.end}, " +
                  "changed=${isInsert}, textLength=${text.length}, " +
                  "startByte=${edit.startByte}, oldEndByte=${edit.oldEndByte}, " +
                  "newEndByte=${edit.newEndByte}")
        }

        workingTree.edit(edit)
        document.doMod(textMod)
        (edit as? TreeSitterInputEdit?)?.recycle()
      }

      document.requestCancellationAndWaitIfParsing()

      if (isDestroyed) {
        return
      }

      document.reparse(workingTree)
      updateStyles(force = true)
    } catch (err: Throwable) {
      log.error("Incremental tree-sitter apply failed. Falling back to full rerun", err)
      analyzer.rerun()
    } finally {
      workingTree.close()
    }
  }

  private fun updateStyles(force: Boolean = false) {
    val now = SystemClock.elapsedRealtime()
    val tooSoonSinceLastUpdate = now - lastStylesUpdateTime < MIN_STYLE_UPDATE_INTERVAL_MS

    if (!force && (isDestroyed || tree?.canAccess() != true || tooSoonSinceLastUpdate)) {
      return
    }

    lastStylesUpdateTime = now

    val tree = tree!!
    val scopedVariables = TsScopedVariables(tree, text, languageSpec)
    val oldTree = (styles.spans as? LineSpansGenerator?)?.tree
    val copied = tree.copy()

    styles.spans =
        LineSpansGenerator(
            copied,
            reference.lineCount,
            reference.reference,
            theme,
            languageSpec,
            scopedVariables,
            spanFactory,
        )

    val oldBlocks = styles.blocks
    updateCodeBlocks()
    oldBlocks?.also { ObjectAllocator.recycleBlockLines(it) }

    stylesReceiver?.setStyles(analyzer, styles) { oldTree?.close() }

    stylesReceiver?.updateBracketProvider(analyzer, TsBracketPairs(copied, languageSpec))
  }

  private fun updateCodeBlocks() {
    if (
        languageSpec.blocksQuery.patternCount == 0 ||
            !languageSpec.blocksQuery.canAccess() ||
            tree?.canAccess() != true
    ) {
      return
    }

    val blocks = mutableListOf<CodeBlock>()
    TSQueryCursor.create().use { cursor ->
      cursor.safeExecQueryCursor(
          query = languageSpec.blocksQuery,
          tree = tree,
          recycleNodeAfterUse = true,
          matchCondition = { !isDestroyed },
          onClosedOrEdited = { blocks.clear() },
          debugName = "TsAnalyzeManager.updateCodeBlocks()",
      ) { match ->
        if (!languageSpec.blocksPredicator.doPredicate(languageSpec.predicates, text, match)) {
          return@safeExecQueryCursor
        }

        match.captures.forEach { capture ->
          val block = ObjectAllocator.obtainBlockLine()
          var node = capture.node
          val start = node.startPoint

          block.startLine = start.row
          block.startColumn = start.column / 2

          val end =
              if (languageSpec.blocksQuery.getCaptureNameForId(capture.index).endsWith(".marked")) {
                // Goto last terminal element
                while (node.childCount > 0) {
                  node = node.getChild(node.childCount - 1)
                }
                node.startPoint
              } else {
                node.endPoint
              }
          block.endLine = end.row
          block.endColumn = end.column / 2
          if (block.endLine - block.startLine > 1) {
            blocks.add(block)
          }

          (capture as? TreeSitterQueryCapture?)?.recycle()
        }
      }
    }

    val distinct = blocks.asSequence().distinct().toMutableList()
    styles.blocks = distinct
    styles.finishBuilding()
  }
}

internal interface Message<T> {

  val data: T
}

internal data class Init(override val data: TextInit) : Message<TextInit>

internal data class Mod(override val data: TextMod) : Message<TextMod>

internal data class TextInit(val text: String, val contentVersion: Long)

internal data class TextMod(
    val start: Int,
    val end: Int,
    val edit: TSInputEdit,
    val changedText: String?,
    val contentVersion: Long,
)
