/*
 * This file is part of AndroidIDE.
 *
 *
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 */
package com.tom.rv2ide.lsp;

import static com.tom.rv2ide.resources.R.drawable;
import static com.tom.rv2ide.resources.R.string;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.tom.rv2ide.common.logging.IdeLogConfig;

import com.blankj.utilcode.util.FileIOUtils;
import com.blankj.utilcode.util.FileUtils;
import com.tom.rv2ide.activities.editor.EditorHandlerActivity;
import com.tom.rv2ide.adapters.DiagnosticsAdapter;
import com.tom.rv2ide.adapters.SearchListAdapter;
import com.tom.rv2ide.editor.ui.IDEEditor;
import com.tom.rv2ide.fragments.sheets.ProgressSheet;
import com.tom.rv2ide.lsp.api.ILanguageClient;
import com.tom.rv2ide.lsp.models.CodeActionItem;
import com.tom.rv2ide.lsp.models.DiagnosticItem;
import com.tom.rv2ide.lsp.models.DiagnosticResult;
import com.tom.rv2ide.lsp.models.PerformCodeActionParams;
import com.tom.rv2ide.lsp.models.ShowDocumentParams;
import com.tom.rv2ide.lsp.models.ShowDocumentResult;
import com.tom.rv2ide.lsp.models.TextEdit;
import com.tom.rv2ide.lsp.util.DiagnosticUtil;
import com.tom.rv2ide.models.DiagnosticGroup;
import com.tom.rv2ide.models.Location;
import com.tom.rv2ide.models.Range;
import com.tom.rv2ide.models.SearchResult;
import com.tom.rv2ide.tasks.TaskExecutor;
import com.tom.rv2ide.ui.CodeEditorView;
import com.tom.rv2ide.utils.FlashbarActivityUtilsKt;
import com.tom.rv2ide.utils.FlashbarUtilsKt;
import com.tom.rv2ide.utils.LSPUtils;
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer;
import io.github.rosemoe.sora.text.Content;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import kotlin.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AndroidIDE specific implementation of the LanguageClient
 */
public class IDELanguageClientImpl implements ILanguageClient {

  public static final int MAX_DIAGNOSTIC_FILES = 10;
  public static final int MAX_DIAGNOSTIC_ITEMS_PER_FILE = 20;
  protected static final Logger LOG = LoggerFactory.getLogger(IDELanguageClientImpl.class);
  private static IDELanguageClientImpl mInstance;
  private final Map<File, List<DiagnosticItem>> diagnostics = new HashMap<>();
  protected EditorHandlerActivity activity;

  private IDELanguageClientImpl(EditorHandlerActivity provider) {
    setActivity(provider);
  }

  public void setActivity(EditorHandlerActivity provider) {
    this.activity = provider;
  }

  public static IDELanguageClientImpl initialize(EditorHandlerActivity provider) {
    if (mInstance != null) {
      throw new IllegalStateException("Client is already initialized");
    }

    mInstance = new IDELanguageClientImpl(provider);

    return getInstance();
  }

  public static IDELanguageClientImpl getInstance() {
    if (mInstance == null) {
      throw new IllegalStateException("Client not initialized");
    }

    return mInstance;
  }

  public static void shutdown() {
    if (mInstance != null) {
      mInstance.activity = null;
    }
    mInstance = null;
  }

  public static boolean isInitialized() {
    return mInstance != null;
  }

// In IDELanguageClientImpl.java - Update the publishDiagnostics method

  @Override
  public void publishDiagnostics(DiagnosticResult result) {
    final long totalStartMs = android.os.SystemClock.elapsedRealtime();
    final String threadName = Thread.currentThread().getName();
    final boolean isMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper();

    if (result == DiagnosticResult.NO_UPDATE || !canUseActivity()) {
      if (result == DiagnosticResult.NO_UPDATE) {
        LOG.info("publishDiagnostics skipped: NO_UPDATE");
      } else {
        LOG.warn("publishDiagnostics skipped: activity unavailable");
      }
      return;
    }

    boolean error = result == null;
    activity.handleDiagnosticsResultVisibility(error || result.getDiagnostics().isEmpty());

    if (error) {
      LOG.warn("publishDiagnostics skipped: result is null");
      return;
    }

    File file = result.getFile().toFile();
    final int diagnosticCount = result.getDiagnostics() == null ? -1 : result.getDiagnostics().size();
    if (IdeLogConfig.shouldLogIde()) {
      LOG.debug(
          "publishDiagnostics received: file={}, exists={}, isFile={}, count={}, thread={}, isMainThread={}",
          file.getAbsolutePath(),
          file.exists(),
          file.isFile(),
          diagnosticCount,
          threadName,
          isMainThread);
    }
    if (!file.exists() || !file.isFile()) {
      LOG.warn("publishDiagnostics dropped: target file missing or not regular file={}", file);
      return;
    }

    final long editorLookupStartMs = android.os.SystemClock.elapsedRealtime();
    final var editorView = activity.getEditorForFile(file);
    final long editorLookupCostMs = android.os.SystemClock.elapsedRealtime() - editorLookupStartMs;
    if (IdeLogConfig.shouldLogIde()) {
      LOG.debug(
          "publishDiagnostics editor match: {} (editorLookupCostMs={})",
          editorView != null ? "HIT" : "MISS",
          editorLookupCostMs);
    }

    long mapRegionsCostMs = -1L;
    long applyToEditorCostMs = -1L;
    int contentLength = -1;

    if (editorView != null) {
      final var editor = editorView.getEditor();
      if (editor != null) {
        final var container = new DiagnosticsContainer();
        try {
          final var content = editor.getText();
          contentLength = content.length();

          final long mapRegionsStartMs = android.os.SystemClock.elapsedRealtime();
          container.addDiagnostics(
              result.getDiagnostics().stream()
                  .map(diagnostic -> diagnostic.asDiagnosticRegion(content))
                  .collect(Collectors.toList()));
          mapRegionsCostMs = android.os.SystemClock.elapsedRealtime() - mapRegionsStartMs;
          LOG.info(
              "publishDiagnostics mapped {} diagnostic regions for file={} (mapRegionsCostMs={}, contentLength={})",
              result.getDiagnostics().size(),
              file.getAbsolutePath(),
              mapRegionsCostMs,
              contentLength);
        } catch (Throwable err) {
          LOG.error("Unable to map DiagnosticItem to DiagnosticRegion", err);
        }

        final long applyToEditorStartMs = android.os.SystemClock.elapsedRealtime();
        editor.setDiagnostics(container);
        applyToEditorCostMs = android.os.SystemClock.elapsedRealtime() - applyToEditorStartMs;
        LOG.info(
            "publishDiagnostics applied diagnostics to editor for file={} (applyToEditorCostMs={})",
            file.getAbsolutePath(),
            applyToEditorCostMs);
      } else {
        LOG.warn(
            "publishDiagnostics editor match MISS: CodeEditorView has null editor for file={}",
            file.getAbsolutePath());
      }
    }

    diagnostics.put(file, result.getDiagnostics());

    final long updateAdapterStartMs = android.os.SystemClock.elapsedRealtime();
    activity.setDiagnosticsAdapter(newDiagnosticsAdapter());
    final long updateAdapterCostMs = android.os.SystemClock.elapsedRealtime() - updateAdapterStartMs;
    final long totalCostMs = android.os.SystemClock.elapsedRealtime() - totalStartMs;

    LOG.info(
        "publishDiagnostics perf: file={}, totalCostMs={}, editorLookupCostMs={}, mapRegionsCostMs={}, applyToEditorCostMs={}, updateAdapterCostMs={}, diagnosticCount={}, contentLength={}, thread={}, isMainThread={}",
        file.getAbsolutePath(),
        totalCostMs,
        editorLookupCostMs,
        mapRegionsCostMs,
        applyToEditorCostMs,
        updateAdapterCostMs,
        diagnosticCount,
        contentLength,
        threadName,
        isMainThread);
  }

  @Nullable
  @Override
  public DiagnosticItem getDiagnosticAt(final File file, final int line, final int column) {
    return DiagnosticUtil.binarySearchDiagnostic(this.diagnostics.get(file), line, column);
  }

  @Override
  public void performCodeAction(PerformCodeActionParams params) {
    if (params == null) {
      return;
    }

    final var action = params.getAction();
    if (!canUseActivity()) {
      LOG.error("Unable to perform code action activity=null action={}", action);
      FlashbarUtilsKt.flashError(string.msg_cannot_perform_fix);
      return;
    }

    final var currentEditor = this.activity.getCurrentEditor();
    final var editor = currentEditor != null ? currentEditor.getEditor() : null;

    if (!params.getAsync()) {
      applyActionEdits(editor, action);
      if (editor != null) {
        action.getCommand();
        editor.executeCommand(action.getCommand());
      }
      return;
    }

    final ProgressSheet progress = new ProgressSheet();
    progress.setSubMessageEnabled(false);
    progress.setCancelable(false);
    progress.setMessage(this.activity.getString(string.msg_performing_actions));
    progress.show(this.activity.getSupportFragmentManager(), "quick_fix_progress");

    TaskExecutor.executeAsyncProvideError(
        () -> applyActionEdits(editor, action),
        (result, throwable) -> {
          progress.dismiss();
          if (result == null || throwable != null || !result) {
            LOG.error("Unable to perform code action result={}", result, throwable);
            FlashbarActivityUtilsKt.flashError(this.activity, string.msg_cannot_perform_fix);
          } else if (editor != null) {
            editor.executeCommand(action.getCommand());
          }
        });
  }

  private Boolean applyActionEdits(@Nullable final IDEEditor editor, final CodeActionItem action) {
    final var changes = action.getChanges();
    if (changes.isEmpty()) {
      return Boolean.FALSE;
    }

    for (var change : changes) {
      final var path = change.getFile();
      if (path == null) {
        continue;
      }

      final File file = path.toFile();
      if (!file.exists()) {
        continue;
      }

      for (TextEdit edit : change.getEdits()) {
        final String editorFilepath =
            editor == null || editor.getFile() == null ? "" : editor.getFile().getAbsolutePath();
        if (file.getAbsolutePath().equals(editorFilepath)) {
          // Edit is in the same editor which requested the code action
          editInEditor(editor, edit);
        } else {
          var openedFrag = findEditorByFile(file);

          if (openedFrag != null && openedFrag.getEditor() != null) {
            // Edit is in another 'opened' file
            editInEditor(openedFrag.getEditor(), edit);
          } else {
            // Edit is in some other file which is not opened
            // open that file and perform the edit
            openedFrag = activity.openFile(file);
            if (openedFrag != null && openedFrag.getEditor() != null) {
              editInEditor(openedFrag.getEditor(), edit);
            }
          }
        }
      }
    }

    return Boolean.TRUE;
  }

  private void editInEditor(final IDEEditor editor, final TextEdit edit) {
    activity
        .runOnUiThread(
            () -> {
              final Range range = edit.getRange();
              final int startLine = range.getStart().getLine();
              final int startCol = range.getStart().getColumn();
              final int endLine = range.getEnd().getLine();
              final int endCol = range.getEnd().getColumn();
              if (startLine == endLine && startCol == endCol) {
                editor.getText().insert(startLine, startCol, edit.getNewText());
              } else {
                editor.getText().replace(startLine, startCol, endLine, endCol, edit.getNewText());
              }
            });
  }

  @Override
  public ShowDocumentResult showDocument(ShowDocumentParams params) {
    boolean success = false;
    final var result = new ShowDocumentResult(false);
    if (!canUseActivity()) {
      return result;
    }

    if (params != null) {
      File file = params.getFile().toFile();
      if (file.exists() && file.isFile() && FileUtils.isUtf8(file)) {
        final var range = params.getSelection();
        var frag =
            activity.getEditorAtIndex(activity.getContent().tabs.getSelectedTabPosition());
        if (frag != null
            && frag.getFile() != null
            && frag.getEditor() != null
            && frag.getFile().getAbsolutePath().equals(file.getAbsolutePath())) {
          if (LSPUtils.isEqual(range.getStart(), range.getEnd())) {
            frag.getEditor().setSelection(range.getStart().getLine(), range.getStart().getColumn());
          } else {
            frag.getEditor().setSelection(range);
          }
        } else {
          activity.openFileAndSelect(file, range);
        }
        success = true;
      }
    }

    result.setSuccess(success);
    return result;
  }

  public DiagnosticsAdapter newDiagnosticsAdapter() {
    return new DiagnosticsAdapter(mapAsGroup(this.diagnostics), activity);
  }

  private List<DiagnosticGroup> mapAsGroup(Map<File, List<DiagnosticItem>> map) {
    final var groups = new ArrayList<DiagnosticGroup>();
    var diagnosticMap = map;
    if (diagnosticMap == null || diagnosticMap.size() == 0) {
      return groups;
    }

    if (diagnosticMap.size() > 10) {
      LOG.warn("Limiting the diagnostics to 10 files");
      diagnosticMap = filterRelevantDiagnostics(map);
    }

    for (File file : diagnosticMap.keySet()) {
      var fileDiagnostics = diagnosticMap.get(file);
      if (fileDiagnostics == null || fileDiagnostics.size() == 0) {
        continue;
      }

      // Trim the diagnostics list if we have too many diagnostic items.
      // Including a lot of diagnostic items will result in UI lag when they are shown
      if (fileDiagnostics.size() > MAX_DIAGNOSTIC_ITEMS_PER_FILE) {
        LOG.warn("Limiting diagnostics to {} items for file {}",
            MAX_DIAGNOSTIC_ITEMS_PER_FILE,
            file.getName());

        fileDiagnostics = fileDiagnostics.subList(0, MAX_DIAGNOSTIC_ITEMS_PER_FILE);
      }
      DiagnosticGroup group = new DiagnosticGroup(drawable.ic_language_java, file, fileDiagnostics);
      groups.add(group);
    }
    return groups;
  }

  @NonNull
  private Map<File, List<DiagnosticItem>> filterRelevantDiagnostics(
      @NonNull final Map<File, List<DiagnosticItem>> map) {
    final var result = new HashMap<File, List<DiagnosticItem>>();
    final var files = map.keySet();

    // Diagnostics of files that are open must always be included
    final var relevantFiles = findOpenFiles(files, MAX_DIAGNOSTIC_FILES);

    // If we can show a few more file diagnostics...
    if (relevantFiles.size() < MAX_DIAGNOSTIC_FILES) {
      final var alphabetical = new TreeSet<>(Comparator.comparing(File::getName));
      alphabetical.addAll(files);
      for (var file : alphabetical) {
        relevantFiles.add(file);
        if (relevantFiles.size() == MAX_DIAGNOSTIC_FILES) {
          break;
        }
      }
    }

    for (var file : relevantFiles) {
      result.put(file, map.get(file));
    }
    return result;
  }

  @NonNull
  private Set<File> findOpenFiles(final Set<File> files, final int max) {
    final var openedFiles = activity.getEditorViewModel().getOpenedFiles();
    final var result = new TreeSet<File>();
    for (int i = 0; i < openedFiles.size(); i++) {
      final var opened = openedFiles.get(i);
      if (files.contains(opened)) {
        result.add(opened);
      }
      if (result.size() == max) {
        break;
      }
    }
    return result;
  }

  /**
   * Called by {@link IDEEditor IDEEditor} to show locations in EditorActivity
   */
  @Override
  public void showLocations(List<Location> locations) {

    // Cannot show anything if the activity() is null
    if (!canUseActivity()) {
      return;
    }

    boolean error = locations == null || locations.isEmpty();
    activity.handleSearchResultVisibility(error);

    if (error) {
      activity
          .setSearchResultAdapter(
              new SearchListAdapter(Collections.emptyMap(), this::noOp, this::noOp));
      return;
    }

    final Map<File, List<SearchResult>> results = new HashMap<>();
    for (int i = 0; i < locations.size(); i++) {
      try {
        final Location loc = locations.get(i);
        if (loc == null) {
          continue;
        }

        final File file = loc.getFile().toFile();
        if (!file.exists() || !file.isFile()) {
          continue;
        }
        var frag = findEditorByFile(file);
        Content content;
        if (frag != null && frag.getEditor() != null) {
          content = frag.getEditor().getText();
        } else {
          content = new Content(FileIOUtils.readFile2String(file));
        }
        final List<SearchResult> matches =
            results.containsKey(file) ? results.get(file) : new ArrayList<>();
        Objects.requireNonNull(matches)
            .add(
                new SearchResult(
                    loc.getRange(),
                    file,
                    content.getLineString(loc.getRange().getStart().getLine()),
                    content
                        .subContent(
                            loc.getRange().getStart().getLine(),
                            loc.getRange().getStart().getColumn(),
                            loc.getRange().getEnd().getLine(),
                            loc.getRange().getEnd().getColumn())
                        .toString()));
        results.put(file, matches);
      } catch (Throwable th) {
        LOG.error("Failed to show file location", th);
      }
    }

    activity.handleSearchResults(results);
  }

  private CodeEditorView findEditorByFile(File file) {
    return activity.getEditorForFile(file);
  }

  private boolean canUseActivity() {
    return activity != null
        && !activity.isFinishing()
        && !activity.isDestroyed()
        && !activity.getSupportFragmentManager().isDestroyed()
        && !activity.getSupportFragmentManager().isStateSaved();
  }

  private Unit noOp(final Object obj) {
    return Unit.INSTANCE;
  }
}
