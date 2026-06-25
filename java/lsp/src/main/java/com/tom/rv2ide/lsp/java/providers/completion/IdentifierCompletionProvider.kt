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

package com.tom.rv2ide.lsp.java.providers.completion

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.lsp.api.IServerSettings
import com.tom.rv2ide.lsp.java.compiler.CompileTask
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.progress.ProgressManager.Companion.abortIfCancelled
import java.nio.file.Path
import openjdk.source.util.TreePath

/** @author Akash Yadav */
class IdentifierCompletionProvider(
    completingFile: Path,
    cursor: Long,
    compiler: JavaCompilerService,
    settings: IServerSettings,
) : IJavaCompletionProvider(cursor, completingFile, compiler, settings) {

  override fun doComplete(
      task: CompileTask,
      path: TreePath,
      partial: String,
      endsWithParen: Boolean,
  ): CompletionResult {
    val list = mutableListOf<CompletionItem>()

    abortIfCancelled()
    abortCompletionIfCancelled()

    val snippets =
        try {
          log.warn("Identifier provider stage start stage=snippets partial={}", partial)
          SnippetCompletionProvider(cursor, file, compiler, settings)
              .complete(task, path, partial, endsWithParen)
              .also {
                log.warn(
                    "Identifier provider stage done stage=snippets partial={} itemCount={}",
                    partial,
                    it.items.size)
              }
        } catch (t: Throwable) {
          log.error("Identifier provider stage failed stage=snippets partial={}", partial, t)
          CompletionResult.EMPTY
        }
    list.addAll(snippets.items)

    val scopeMembers =
        try {
          log.warn("Identifier provider stage start stage=scope partial={}", partial)
          ScopeCompletionProvider(file, cursor, compiler, settings)
              .complete(task, path, partial, endsWithParen)
              .also {
                log.warn(
                    "Identifier provider stage done stage=scope partial={} itemCount={}",
                    partial,
                    it.items.size)
              }
        } catch (t: Throwable) {
          log.error("Identifier provider stage failed stage=scope partial={}", partial, t)
          throw t
        }
    list.addAll(scopeMembers.items)

    abortIfCancelled()
    abortCompletionIfCancelled()
    val staticImports =
        try {
          log.warn("Identifier provider stage start stage=staticImports partial={}", partial)
          StaticImportCompletionProvider(file, cursor, compiler, settings, path.compilationUnit)
              .complete(task, path, partial, endsWithParen)
              .also {
                log.warn(
                    "Identifier provider stage done stage=staticImports partial={} itemCount={}",
                    partial,
                    it.items.size)
              }
        } catch (t: Throwable) {
          log.error("Identifier provider stage failed stage=staticImports partial={}", partial, t)
          throw t
        }
    list.addAll(staticImports.items)
    if (IdeLogConfig.shouldLogInfo()) {
      log.info(
          "identifier completion partial='{}' preClassItems={} trimToMax={} maxItems={} allLower={} startsUpper={}"
          ,
          partial,
          list.size,
          CompletionResult.TRIM_TO_MAX,
          CompletionResult.MAX_ITEMS,
          settings.shouldMatchAllLowerCase(),
          partial.isNotEmpty() && Character.isUpperCase(partial[0]))
    }
    if (CompletionResult.TRIM_TO_MAX && list.size < CompletionResult.MAX_ITEMS) {

      val allLower: Boolean = settings.shouldMatchAllLowerCase()
      if (allLower || partial.isNotEmpty() && Character.isUpperCase(partial[0])) {
        abortIfCancelled()
        abortCompletionIfCancelled()
        val classNames =
            try {
              log.warn("Identifier provider stage start stage=classNames partial={}", partial)
              ClassNamesCompletionProvider(file, cursor, compiler, settings, path.compilationUnit)
                  .complete(task, path, partial, endsWithParen)
                  .also {
                    log.warn(
                        "Identifier provider stage done stage=classNames partial={} itemCount={}",
                        partial,
                        it.items.size)
                  }
            } catch (t: Throwable) {
              log.error("Identifier provider stage failed stage=classNames partial={}", partial, t)
              throw t
            }
        list.addAll(classNames.items)
      }
    }

    abortIfCancelled()
    abortCompletionIfCancelled()
    val keywords =
        try {
          log.warn("Identifier provider stage start stage=keywords partial={}", partial)
          KeywordCompletionProvider(file, cursor, compiler, settings)
              .complete(task, path, partial, endsWithParen)
              .also {
                log.warn(
                    "Identifier provider stage done stage=keywords partial={} itemCount={}",
                    partial,
                    it.items.size)
              }
        } catch (t: Throwable) {
          log.error("Identifier provider stage failed stage=keywords partial={}", partial, t)
          throw t
        }
    list.addAll(keywords.items)

    return CompletionResult(list)
  }
}
