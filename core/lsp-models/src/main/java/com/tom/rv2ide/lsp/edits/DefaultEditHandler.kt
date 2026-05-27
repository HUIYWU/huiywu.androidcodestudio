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

package com.tom.rv2ide.lsp.edits

import android.os.Looper
import com.blankj.utilcode.util.ThreadUtils
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.lsp.models.Command
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.InsertTextFormat.SNIPPET
import com.tom.rv2ide.lsp.models.SnippetDescription
import com.tom.rv2ide.lsp.util.RewriteHelper
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import org.slf4j.LoggerFactory

/**
 * Default edit handler for completion items.
 *
 * @author Akash Yadav
 */
open class DefaultEditHandler : IEditHandler {

  companion object {

    private val log = LoggerFactory.getLogger(DefaultEditHandler::class.java)
  }

  override fun performEdits(
      item: CompletionItem,
      editor: CodeEditor,
      text: Content,
      line: Int,
      column: Int,
      index: Int,
  ) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      ThreadUtils.runOnUiThread {
        try {
          if (IdeLogConfig.shouldLogIde()) {
            log.info(
                "DefaultEditHandler.performEdits(ui-dispatch): label='{}', insertText='{}', format={}, line={}, column={}, index={}, kind={}, command={}, additionalTextEditsCount={}, additionalEditHandler={}, editorClass={}",
                item.ideLabel,
                item.insertText,
                item.insertTextFormat,
                line,
                column,
                index,
                item.completionKind,
                item.command?.command,
                item.additionalTextEdits?.size ?: 0,
                item.additionalEditHandler?.javaClass?.name,
                editor::class.java.name,
            )
          }
          performEditsInternal(item, editor, text, line, column, index)
        } catch (t: Throwable) {
          log.error(
              "DefaultEditHandler.performEdits(ui-dispatch) failed: label='{}', insertText='{}', format={}, line={}, column={}, index={}, kind={}, command={}, additionalTextEditsCount={}, additionalEditHandler={}, editorClass={}",
              item.ideLabel,
              item.insertText,
              item.insertTextFormat,
              line,
              column,
              index,
              item.completionKind,
              item.command?.command,
              item.additionalTextEdits?.size ?: 0,
              item.additionalEditHandler?.javaClass?.name,
              editor::class.java.name,
              t,
          )
          throw t
        }
      }
      return
    }

    try {
      if (IdeLogConfig.shouldLogIde()) {
        log.info(
            "DefaultEditHandler.performEdits: label='{}', insertText='{}', format={}, line={}, column={}, index={}, kind={}, command={}, additionalTextEditsCount={}, additionalEditHandler={}, editorClass={}",
            item.ideLabel,
            item.insertText,
            item.insertTextFormat,
            line,
            column,
            index,
            item.completionKind,
            item.command?.command,
            item.additionalTextEdits?.size ?: 0,
            item.additionalEditHandler?.javaClass?.name,
            editor::class.java.name,
        )
      }
      performEditsInternal(item, editor, text, line, column, index)
    } catch (t: Throwable) {
      log.error(
          "DefaultEditHandler.performEdits failed: label='{}', insertText='{}', format={}, line={}, column={}, index={}, kind={}, command={}, additionalTextEditsCount={}, additionalEditHandler={}, editorClass={}",
          item.ideLabel,
          item.insertText,
          item.insertTextFormat,
          line,
          column,
          index,
          item.completionKind,
          item.command?.command,
          item.additionalTextEdits?.size ?: 0,
          item.additionalEditHandler?.javaClass?.name,
          editor::class.java.name,
          t,
      )
      throw t
    }
  }

  protected open fun performEditsInternal(
      item: CompletionItem,
      editor: CodeEditor,
      text: Content,
      line: Int,
      column: Int,
      index: Int,
  ) {
    if (IdeLogConfig.shouldLogIde()) {
      log.info(
          "DefaultEditHandler.performEditsInternal start: label='{}', insertText='{}', format={}, line={}, column={}, index={}, textLength={}, hasCommand={}, additionalTextEditsCount={}, hasAdditionalEditHandler={}, snippetDescription={}",
          item.ideLabel,
          item.insertText,
          item.insertTextFormat,
          line,
          column,
          index,
          text.length,
          item.command != null,
          item.additionalTextEdits?.size ?: 0,
          item.additionalEditHandler != null,
          item.snippetDescription,
      )
    }
    if (item.insertTextFormat == SNIPPET) {
      if (IdeLogConfig.shouldLogIde()) {
        log.info("DefaultEditHandler.performEditsInternal: entering snippet branch for label='{}'", item.ideLabel)
      }
      insertSnippet(item, editor, text, line, column, index)
      return
    }

    val lineText = text.getLine(line)
    if (IdeLogConfig.shouldLogIde()) {
      log.info(
          "DefaultEditHandler.performEditsInternal: lineText='{}', lineLength={}, requestedColumn={}",
          lineText,
          lineText.length,
          column,
      )
    }
    val start = getIdentifierStart(lineText, column)
    if (IdeLogConfig.shouldLogIde()) {
      log.info(
          "DefaultEditHandler.performEditsInternal: computed identifier start={}, deleting range line={} [{}..{})",
          start,
          line,
          start,
          column,
      )
    }
    text.delete(line, start, line, column)
    if (IdeLogConfig.shouldLogIde()) {
      log.info(
          "DefaultEditHandler.performEditsInternal: delete succeeded, committing text='{}'",
          item.insertText,
      )
    }
    editor.commitText(item.insertText)
    if (IdeLogConfig.shouldLogIde()) {
      log.info("DefaultEditHandler.performEditsInternal: commitText succeeded")
    }

    text.beginBatchEdit()
    try {
      if (item.additionalEditHandler != null) {
        if (IdeLogConfig.shouldLogIde()) {
          log.info(
              "DefaultEditHandler.performEditsInternal: applying additionalEditHandler={} for label='{}'",
              item.additionalEditHandler!!.javaClass.name,
              item.ideLabel,
          )
        }
        item.additionalEditHandler!!.performEdits(item, editor, text, line, column, index)
      } else if (item.additionalTextEdits != null && item.additionalTextEdits!!.isNotEmpty()) {
        if (IdeLogConfig.shouldLogIde()) {
          log.info(
              "DefaultEditHandler.performEditsInternal: applying {} additionalTextEdits for label='{}': {}",
              item.additionalTextEdits!!.size,
              item.ideLabel,
              item.additionalTextEdits,
          )
        }
        RewriteHelper.performEdits(item.additionalTextEdits!!, editor)
      } else {
        if (IdeLogConfig.shouldLogIde()) {
          log.info("DefaultEditHandler.performEditsInternal: no additional edits for label='{}'", item.ideLabel)
        }
      }
    } finally {
      text.endBatchEdit()
    }

    if (IdeLogConfig.shouldLogIde()) {
      log.info("DefaultEditHandler.performEditsInternal: executing command={} for label='{}'", item.command, item.ideLabel)
    }
    executeCommand(editor, item.command)
    if (IdeLogConfig.shouldLogIde()) {
      log.info("DefaultEditHandler.performEditsInternal end: label='{}'", item.ideLabel)
    }
  }

  protected open fun insertSnippet(
      item: CompletionItem,
      editor: CodeEditor,
      text: Content,
      line: Int,
      column: Int,
      index: Int,
  ) {
    if (IdeLogConfig.shouldLogIde()) {
      log.info(
          "DefaultEditHandler.insertSnippet start: label='{}', insertText='{}', line={}, column={}, index={}, snippetDescription={}",
          item.ideLabel,
          item.insertText,
          line,
          column,
          index,
          item.snippetDescription,
      )
    }
    val snippetDescription: SnippetDescription =
        item.snippetDescription
            ?: SnippetDescription(
                selectedLength = 0,
                deleteSelected = false,
                snippet = null,
                allowCommandExecution = false,
            ).also {
              if (IdeLogConfig.shouldLogIde()) {
                log.warn(
                    "DefaultEditHandler.insertSnippet: snippetDescription missing, using fallback. label='{}', insertText='{}', line={}, column={}, index={}",
                    item.ideLabel,
                    item.insertText,
                    line,
                    column,
                    index,
                )
              }
            }
    val snippet = CodeSnippetParser.parse(item.insertText)
    var prefixLength = snippetDescription.selectedLength
    if (prefixLength <= 0) {
      val lineText = text.getLine(line)
      val fallbackStart = getIdentifierStart(lineText, column)
      prefixLength = column - fallbackStart
      if (IdeLogConfig.shouldLogIde()) {
        log.info(
            "DefaultEditHandler.insertSnippet: selectedLength missing/zero, fallback computed prefixLength={} from line={}, column={}, fallbackStart={}, lineText='{}'",
            prefixLength,
            line,
            column,
            fallbackStart,
            lineText,
        )
      }
    }
    if (IdeLogConfig.shouldLogIde()) {
      log.info(
          "DefaultEditHandler.insertSnippet: prefixLength={}, deleteSelected={}, allowCommandExecution={}",
          prefixLength,
          snippetDescription.deleteSelected,
          snippetDescription.allowCommandExecution,
      )
    }
    val selectedText = text.subSequence(index - prefixLength, index).toString()
    if (IdeLogConfig.shouldLogIde()) {
      log.info("DefaultEditHandler.insertSnippet: selectedText='{}'", selectedText)
    }
    var actionIndex = index
    if (snippetDescription.deleteSelected) {
      text.delete(index - prefixLength, index)
      actionIndex -= prefixLength
      if (IdeLogConfig.shouldLogIde()) {
        log.info("DefaultEditHandler.insertSnippet: deleted selected prefix, newActionIndex={}", actionIndex)
      }
    }
    editor.snippetController.startSnippet(actionIndex, snippet, selectedText)
    if (IdeLogConfig.shouldLogIde()) {
      log.info("DefaultEditHandler.insertSnippet: startSnippet succeeded")
    }

    if (snippetDescription.allowCommandExecution) {
      if (IdeLogConfig.shouldLogIde()) {
        log.info("DefaultEditHandler.insertSnippet: executing command={} for label='{}'", item.command, item.ideLabel)
      }
      executeCommand(editor, item.command)
    }
    if (IdeLogConfig.shouldLogIde()) {
      log.info("DefaultEditHandler.insertSnippet end: label='{}'", item.ideLabel)
    }
  }

  protected open fun executeCommand(editor: CodeEditor, command: Command?) {
    if (command == null) {
      return
    }

    try {
      val klass = editor::class.java
      val method = klass.getMethod("executeCommand", Command::class.java)
      method.isAccessible = true
      method.invoke(editor, command)
    } catch (th: Throwable) {
      log.error("Unable to invoke 'executeCommand(Command) method in IDEEditor.", th)
    }
  }

  protected open fun getIdentifierStart(text: CharSequence, end: Int): Int {
    var start = end
    while (start > 0) {
      if (isPartialPart(text[start - 1])) {
        start--
        continue
      }
      break
    }
    return start
  }

  protected open fun isPartialPart(c: Char) = Character.isJavaIdentifierPart(c)
}
