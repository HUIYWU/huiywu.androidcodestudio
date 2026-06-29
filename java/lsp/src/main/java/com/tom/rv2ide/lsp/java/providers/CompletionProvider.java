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
package com.tom.rv2ide.lsp.java.providers;

import static com.tom.rv2ide.lsp.api.HelpersKt.describeSnippet;
import static com.tom.rv2ide.progress.ProgressManager.abortIfCancelled;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.blankj.utilcode.util.ReflectUtils;
import com.tom.rv2ide.common.logging.IdeLogConfig;
import com.tom.rv2ide.lsp.api.AbstractServiceProvider;

import com.tom.rv2ide.lsp.api.ICompletionProvider;
import com.tom.rv2ide.lsp.api.IServerSettings;
import com.tom.rv2ide.lsp.internal.model.CachedCompletion;
import com.tom.rv2ide.lsp.java.compiler.CompileTask;
import com.tom.rv2ide.lsp.java.compiler.CompletionInfo;
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerConfig;
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService;
import com.tom.rv2ide.lsp.java.compiler.SourceFileObject;
import com.tom.rv2ide.lsp.java.compiler.SynchronizedTask;
import com.tom.rv2ide.lsp.java.models.CompilationRequest;
import com.tom.rv2ide.lsp.java.models.PartialReparseRequest;
import com.tom.rv2ide.lsp.java.providers.completion.IJavaCompletionProvider;
import com.tom.rv2ide.lsp.java.providers.completion.IdentifierCompletionProvider;
import com.tom.rv2ide.lsp.java.providers.completion.ImportCompletionProvider;
import com.tom.rv2ide.lsp.java.providers.completion.KeywordCompletionProvider;
import com.tom.rv2ide.lsp.java.providers.completion.MemberReferenceCompletionProvider;
import com.tom.rv2ide.lsp.java.providers.completion.MemberSelectCompletionProvider;
import com.tom.rv2ide.lsp.java.providers.completion.SwitchConstantCompletionProvider;
import com.tom.rv2ide.lsp.java.providers.completion.ts.TSCompletionContext;
import com.tom.rv2ide.lsp.java.providers.completion.ts.TSCompletionContextClassifier;
import com.tom.rv2ide.lsp.java.utils.ASTFixer;
import com.tom.rv2ide.lsp.java.utils.CancelChecker;
import com.tom.rv2ide.lsp.java.visitors.FindCompletionsAt;
import com.tom.rv2ide.lsp.models.CompletionParams;
import com.tom.rv2ide.lsp.models.CompletionResult;
import com.tom.rv2ide.utils.DocumentUtils;
import io.github.rosemoe.sora.lang.completion.snippet.CodeSnippet;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import openjdk.source.tree.Tree;
import openjdk.source.util.TreePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompletionProvider extends AbstractServiceProvider implements ICompletionProvider {

  public static final int MAX_COMPLETION_ITEMS = CompletionResult.MAX_ITEMS;
  private static final Logger LOG = LoggerFactory.getLogger(CompletionProvider.class);
  private final AtomicBoolean completing = new AtomicBoolean(false);
  private JavaCompilerService compiler;
  private CachedCompletion cache;
  private Consumer<CachedCompletion> nextCacheConsumer;

  public CompletionProvider() {
    super();
  }

  public synchronized CompletionProvider reset(JavaCompilerService compiler,
      IServerSettings settings, CachedCompletion cache,
      Consumer<CachedCompletion> nextCacheConsumer
  ) {
    this.compiler = compiler;
    this.cache = cache;
    this.nextCacheConsumer = nextCacheConsumer;

    super.applySettings(settings);
    return this;
  }

  @Override
  public boolean canComplete(Path file) {
    return ICompletionProvider.super.canComplete(file) && DocumentUtils.isJavaFile(file);
  }

  @NonNull
  @Override
  public CompletionResult complete(@NonNull CompletionParams params) {
    final var synchronizedTask = compiler.getSynchronizedTask();
    if (synchronizedTask.isBusy()) {
      final TSCompletionContext busyContext = classifyCompletionContext(params);
      CompletionResult busyFallback = tryServeBusyFallback(params);
      if (busyFallback == null && busyContext == TSCompletionContext.MEMBER_ACCESS) {
        busyFallback = tryServeSameFileBusyFallback(params);
      }
      if (busyFallback != null) {
        if (IdeLogConfig.shouldLogInfo()) {
          LOG.info("Completion busy; serving fallback result itemCount={} incomplete={} cached={} tsContext={}",
              busyFallback.getItems().size(),
              busyFallback.isIncomplete(),
              busyFallback.isCached(),
              busyContext);
        }
        return busyFallback;
      }
      if (busyContext == TSCompletionContext.BROKEN_SYNTAX_NEAR_CURSOR) {
        if (IdeLogConfig.shouldLogInfo()) {
          LOG.info("Completion busy near broken syntax; returning empty result file={} cursor={}",
              params.getFile(),
              params.getPosition().requireIndex());
        }
        return CompletionResult.EMPTY;
      }
      if (IdeLogConfig.shouldLogWarn()) {
        LOG.warn("Completion busy and no fallback result is available tsContext={}", busyContext);
        synchronizedTask.logStats();
      }
      return CompletionResult.EMPTY;
    }
 
    completing.set(true);
    try {
      abortIfCancelled();
      abortCompletionIfCancelled();
      return completeInternal(params);
    } catch (Throwable err) {
      if (CancelChecker.isCancelled(err)) {
        if (IdeLogConfig.shouldLogIde()) {
          LOG.info("Completion request cancelled");
        }
      } else {
        final Path file = params.getFile();
        final int line = params.getPosition().getLine();
        final int column = params.getPosition().getColumn();
        final long index = params.getPosition().getIndex();
        final String prefix = params.getPrefix();
        LOG.error(
            "An error occurred while computing completions file={} line={} column={} index={} prefix={} compilerHash={} currentContextPresent={} synchronizedTaskBusy={} errorType={} errorMessage={}",
            file,
            line,
            column,
            index,
            prefix,
            System.identityHashCode(compiler),
            compiler != null && compiler.compiler.currentContext != null,
            synchronizedTask.isBusy(),
            err.getClass().getName(),
            err.getMessage(),
            err);
      }
      throw err;
    } finally {
      completing.set(false);
    }
  }

  @Nullable
  private CompletionResult tryServeBusyFallback(@NonNull final CompletionParams params) {
    if (this.cache == null) {
      return null;
    }

    final String partial = params.getPrefix() == null ? "" : partialIdentifier(params.requirePrefix(), params.requirePrefix().length());
    if (this.cache.canUseCache(params)) {
      return mapCachedBusyResult(partial);
    }

    if (this.cache.params != null
        && DocumentUtils.isSameFile(this.cache.params.getFile(), params.getFile())) {
      return mapCachedBusyResult(partial);
    }

    return null;
  }

  @Nullable
  private CompletionResult tryServeSameFileBusyFallback(@NonNull final CompletionParams params) {
    if (this.cache == null || this.cache.params == null) {
      return null;
    }
    if (!DocumentUtils.isSameFile(this.cache.params.getFile(), params.getFile())) {
      return null;
    }
    final String partial = params.getPrefix() == null ? "" : partialIdentifier(params.requirePrefix(), params.requirePrefix().length());
    return mapCachedBusyResult(partial);
  }

  @NonNull
  private CompletionResult mapCachedBusyResult(@NonNull final String partial) {
    final CompletionResult result = CompletionResult.mapAndFilter(this.cache.result, partial, item -> {
      final var description = item.getSnippetDescription();
      var deleteSelected = true;
      var allowCommands = false;
      CodeSnippet snippet = null;
      if (description != null) {
        deleteSelected = description.getDeleteSelected();
        allowCommands = description.getAllowCommandExecution();
        snippet = description.getSnippet();
      }
      item.setSnippetDescription(describeSnippet(partial, deleteSelected, snippet, allowCommands));
    });
    result.markCached();
    return result;
  }

  private TSCompletionContext classifyCompletionContext(@NonNull CompletionParams params) {
    try {
      final Path file = params.getFile();
      final long cursor = params.getPosition().requireIndex();
      final var sourceObject = new SourceFileObject(file);
      final String originalContents = sourceObject.getCharContent(true).toString();
      return TSCompletionContextClassifier.classify(file, originalContents, cursor);
    } catch (Throwable ignored) {
      return TSCompletionContext.UNKNOWN;
    }
  }

  @NonNull
  private CompletionResult completeInternal(final @NonNull CompletionParams params) {
    Path file = params.getFile();
    int line = params.getPosition().getLine();
    int column = params.getPosition().getColumn();
    if (IdeLogConfig.shouldLogIde()) {
      LOG.debug("Complete at {}({},{})...", file.getFileName(), line, column);
    }

    Instant started = Instant.now();

    if (this.cache != null && this.cache.canUseCache(params)) {
      final String prefix = params.requirePrefix();
      final String partial = partialIdentifier(prefix, prefix.length());
      final CompletionResult result = CompletionResult.mapAndFilter(this.cache.result, partial,
          item -> {
            final var description = item.getSnippetDescription();
            var deleteSelected = true;
            var allowCommands = false;
            CodeSnippet snippet = null;

            if (description != null) {
              deleteSelected = description.getDeleteSelected();
              allowCommands = description.getAllowCommandExecution();
              snippet = description.getSnippet();
            }

            item.setSnippetDescription(
                describeSnippet(partial, deleteSelected, snippet, allowCommands));
          });

      result.markCached();

      if (!result.isIncomplete() && !result.getItems().isEmpty()) {
        if (IdeLogConfig.shouldLogIde()) {
          LOG.debug("...using cached completion");
        }

        logCompletionDuration(started, result);
        return result;
      } else {
        if (IdeLogConfig.shouldLogIde()) {
          LOG.debug("...cached completions are empty");
        }

      }
    } else {
      if (IdeLogConfig.shouldLogIde()) {
        LOG.debug("...cannot use cached completions");
      }

    }

    abortIfCancelled();
    abortCompletionIfCancelled();
    final long cursor = params.getPosition().requireIndex();
    final var sourceObject = new SourceFileObject(file);
    final String originalContents = sourceObject.getCharContent(true).toString();
    final var contentBuilder = new StringBuilder(originalContents);

    int endOfLine = endOfLine(contentBuilder, (int) cursor);
    contentBuilder.insert(endOfLine, ';');

    final StringBuilder contents;
    final var context = compiler.compiler.currentContext;
    if (context != null) {
      abortIfCancelled();
      abortCompletionIfCancelled();
      contents = new ASTFixer(context).fix(contentBuilder);
    } else {
      contents = contentBuilder;
    }
    final String contentString = contents.toString();
    final TSCompletionContext tsContext = TSCompletionContextClassifier.classify(file, contentString, cursor);
    if (tsContext == TSCompletionContext.COMMENT_OR_STRING) {
      if (IdeLogConfig.shouldLogInfo()) {
        LOG.info("Skipping Java completion in comment/string context file={} cursor={}", file, cursor);
      }
      return CompletionResult.EMPTY;
    }
    if (IdeLogConfig.shouldLogInfo() && tsContext != TSCompletionContext.UNKNOWN) {
      LOG.info("Tree-sitter completion context file={} cursor={} context={}", file, cursor, tsContext);
    }
    final boolean astFixerApplied = context != null;
    final boolean astFixerChangedContents = astFixerApplied && !contentString.contentEquals(contentBuilder);
    final int prefixLength = params.requirePrefix().length();
    final long partialRequestCursor = cursor - prefixLength;
    final int injectedCharCount = contentString.length() - originalContents.length();
    final PartialReparseRequest partialRequest = new PartialReparseRequest(
        partialRequestCursor, contentString);
    if (IdeLogConfig.shouldLogDebug()) {
      LOG.debug(
          "Completion compile request file={} cursor={} prefixLength={} partialRequestCursor={} contentLength={} originalContentLength={} injectedCharCount={} semicolonInserted={} astFixerApplied={} astFixerChangedContents={} currentContextPresent={}",
          file,
          cursor,
          prefixLength,
          partialRequestCursor,
          contentString.length(),
          originalContents.length(),
          injectedCharCount,
          true,
          astFixerApplied,
          astFixerChangedContents,
          context != null);
    }

    abortIfCancelled();
    abortCompletionIfCancelled();
 
    CompletionResult result = compileAndComplete(contentString, params, partialRequest);
    if (result == null) {
      result = CompletionResult.EMPTY;
    }

    abortIfCancelled();
    abortCompletionIfCancelled();
    logCompletionDuration(started, result);

    abortIfCancelled();
    abortCompletionIfCancelled();
    if (this.nextCacheConsumer != null) {
      this.nextCacheConsumer.accept(CachedCompletion.cache(params, result));
    }

    return result;
  }

  @NonNull
  private String partialIdentifier(String contents, int end) {
    int start = end;
    while (start > 0 && Character.isJavaIdentifierPart(contents.charAt(start - 1))) {
      start--;
    }
    return contents.substring(start, end);
  }

  private void logCompletionDuration(Instant started, @NonNull CompletionResult result) {
    long elapsedMs = Duration.between(started, Instant.now()).toMillis();
    if (IdeLogConfig.shouldLogIde()) {
      LOG.debug("Found {} items{}{}in {} ms",
          result.getItems().size(),
          result.isIncomplete() ? " (incomplete) " : "",
          result.isCached() ? " (cached) " : " ",
          elapsedMs
      );
    }

  }

  private int endOfLine(@NonNull CharSequence contents, int cursor) {
    while (cursor < contents.length()) {
      char c = contents.charAt(cursor);
      if (c == '\r' || c == '\n') {
        break;
      }
      cursor++;
    }
    return cursor;
  }

  private CompletionResult compileAndComplete(String contents, CompletionParams params,
      PartialReparseRequest partialRequest
  ) {
    final long cursor = params.getPosition().requireIndex();
    final var file = params.getFile();
    final var started = Instant.now();
    final var source = new SourceFileObject(file, contents, Instant.now());
    final var partial = partialIdentifier(contents, (int) cursor);
    final var endsWithParen = endsWithParen(contents, (int) cursor);
    final TSCompletionContext tsContext = TSCompletionContextClassifier.classify(file, contents, cursor);

    abortIfCancelled();
    abortCompletionIfCancelled();

    final CompilationRequest request = new CompilationRequest(
        Collections.singletonList(source),
        partialRequest,
        true);
    request.configureContext = ctx -> {
      final var config = JavaCompilerConfig.instance(ctx);
      config.setCompletionInfo(new CompletionInfo(params.getPosition()));
    };

    SynchronizedTask synchronizedTask = compiler.compile(request);
    return synchronizedTask.get(task -> {
      if (task == null || task.task == null || task.task.getContext() == null) {
        LOG.warn(
            "Compilation resulted in an invalid JavacTask file={} cursor={} taskPresent={} javacTaskPresent={} contextPresent={}",
            file,
            cursor,
            task != null,
            task != null && task.task != null,
            task != null && task.task != null && task.task.getContext() != null);
        return CompletionResult.EMPTY;
      }
      abortIfCancelled();
      abortCompletionIfCancelled();
      if (IdeLogConfig.shouldLogIde()) {
        LOG.debug("...compiled in {}ms", Duration.between(started, Instant.now()).toMillis());
      }
      final var completionRoot = task.root(file);
      TreePath path = new FindCompletionsAt(task.task).scan(completionRoot, cursor);
      if (path == null || path.getLeaf() == null) {
        LOG.warn(
            "Completion scan returned null path file={} cursor={} rootPresent={} diagnosticsCountUnknown=true",
            file,
            cursor,
            completionRoot != null);
        return CompletionResult.EMPTY;
      }

      abortIfCancelled();
      abortCompletionIfCancelled();
      String newPartial = partial;

      if (path.getLeaf().getKind() == Tree.Kind.IMPORT) {
        newPartial = qualifiedPartialIdentifier(contents, (int) cursor);
        if (newPartial.endsWith(ASTFixer.IDENT)) {
          newPartial = newPartial.substring(0, newPartial.length() - ASTFixer.IDENT.length());
        }
      }

      final var result = doComplete(file, contents, cursor, newPartial, endsWithParen, task, path, tsContext);

      // IMPORTANT: Unregister the completion info from the compiler configuration
      if (task.task.getContext() != null) {
        final var compilerConfig = JavaCompilerConfig.instance(task.task.getContext());
        compilerConfig.setCompletionInfo(null);
      }

      return result;
    });
  }

  @NonNull
  private CompletionResult doComplete(final Path file, final String contents, final long cursor,
      final String partial, final boolean endsWithParen,
      final CompileTask task, final TreePath path,
      final TSCompletionContext tsContext
  ) {
    final Class<? extends IJavaCompletionProvider> klass;
    abortIfCancelled();
    abortCompletionIfCancelled();
    switch (path.getLeaf().getKind()) {
      case IDENTIFIER:
        if (tsContext == TSCompletionContext.IMPORT_DECLARATION
            || tsContext == TSCompletionContext.PACKAGE_DECLARATION) {
          klass = ImportCompletionProvider.class;
          break;
        }
        klass = IdentifierCompletionProvider.class;
        break;
      case MEMBER_SELECT:
        klass = MemberSelectCompletionProvider.class;
        break;
      case MEMBER_REFERENCE:
        LOG.warn(
            "Skipping member reference completion file={} cursor={} leafKind={} partial={} endsWithParen={} reason=temporarily disabled to avoid repeated full fallback and memory pressure",
            file,
            cursor,
            path.getLeaf().getKind(),
            partial,
            endsWithParen);
        return CompletionResult.EMPTY;
      case SWITCH:
        klass = SwitchConstantCompletionProvider.class;
        break;
      case IMPORT:
        klass = ImportCompletionProvider.class;
        break;
      default:
        if (tsContext == TSCompletionContext.IMPORT_DECLARATION
            || tsContext == TSCompletionContext.PACKAGE_DECLARATION) {
          klass = ImportCompletionProvider.class;
          break;
        }
        klass = KeywordCompletionProvider.class;
        break;
    }

    final IJavaCompletionProvider provider = ReflectUtils.reflect(klass)
        .newInstance(file, cursor, compiler, getSettings())
        .get();

    if (provider instanceof ImportCompletionProvider) {
      final String importPath = qualifiedPartialIdentifier(contents, (int) cursor);
      ((ImportCompletionProvider) provider).setImportPath(importPath);
      if (IdeLogConfig.shouldLogInfo()) {
        LOG.info("Routing completion to ImportCompletionProvider file={} cursor={} tsContext={} leafKind={} importPath={}",
            file,
            cursor,
            tsContext,
            path.getLeaf().getKind(),
            importPath);
      }
    }

    abortIfCancelled();
    abortCompletionIfCancelled();
    try {
      LOG.warn(
          "Completion provider start file={} cursor={} leafKind={} provider={} partial={} endsWithParen={}",
          file,
          cursor,
          path.getLeaf().getKind(),
          klass.getName(),
          partial,
          endsWithParen);
      return provider.complete(task, path, partial, endsWithParen);
    } catch (Throwable err) {
      LOG.error(
          "Completion provider failed file={} cursor={} leafKind={} provider={} partial={} endsWithParen={}",
          file,
          cursor,
          path.getLeaf().getKind(),
          klass.getName(),
          partial,
          endsWithParen,
          err);
      throw err;
    }
  }

  private boolean endsWithParen(@NonNull String contents, int cursor) {
    for (int i = cursor; i < contents.length(); i++) {
      if (!Character.isJavaIdentifierPart(contents.charAt(i))) {
        return contents.charAt(i) == '(';
      }
    }
    return false;
  }

  @NonNull
  private String qualifiedPartialIdentifier(String contents, int end) {
    int start = end;
    while (start > 0 && isQualifiedIdentifierChar(contents.charAt(start - 1))) {
      start--;
    }
    return contents.substring(start, end);
  }

  private boolean isQualifiedIdentifierChar(char c) {
    return c == '.' || Character.isJavaIdentifierPart(c);
  }
}
