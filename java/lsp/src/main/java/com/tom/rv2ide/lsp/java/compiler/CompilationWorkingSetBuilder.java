package com.tom.rv2ide.lsp.java.compiler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.tom.rv2ide.common.logging.IdeLogConfig;
import com.tom.rv2ide.lsp.java.models.CompilationRequest;
import com.tom.rv2ide.preferences.internal.JavaPreferences;
import com.tom.rv2ide.projects.FileManager;
import com.tom.rv2ide.projects.ModuleProject;
import com.tom.rv2ide.utils.SourceClassTrie;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdkx.tools.JavaFileObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CompilationWorkingSetBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(CompilationWorkingSetBuilder.class);
  static final int MAX_SAME_PACKAGE_SOURCES = 48;
  static final int MAX_IMPORTED_SOURCES = 64;
  static final int MAX_TOTAL_SOURCES = 96;

  @Nullable private WorkingSetCacheEntry cacheEntry;

  @NonNull
  CompilationRequest expand(
      @NonNull final JavaCompilerService compiler, @NonNull final CompilationRequest request) {
    final ModuleProject module = compiler.getModule();
    if (module == null) {
      logDecision("skip=no-module", null, 0, 0, request.sources.size(), false);
      return request;
    }
    if (!JavaPreferences.INSTANCE.isJavaCompilationWorkingSetEnabled()) {
      logDecision("skip=disabled", null, 0, 0, request.sources.size(), false);
      return request;
    }
    if (!request.allowWorkingSet) {
      logDecision("skip=request-disallowed", null, 0, 0, request.sources.size(), false);
      return request;
    }
    if (request.allowPartialReparse) {
      logDecision("skip=partial-reparse", null, 0, 0, request.sources.size(), false);
      return request;
    }

    final Collection<JavaFileObject> originalSources = request.sources;
    if (originalSources.isEmpty() || originalSources.size() != 1) {
      logDecision("skip=source-count", null, 0, 0, originalSources.size(), false);
      return request;
    }

    final JavaFileObject primary = originalSources.iterator().next();
    final Path primaryPath = sourcePathOf(primary);
    if (primaryPath == null) {
      logDecision("skip=no-source-path", null, 0, 0, 1, false);
      return request;
    }
    final CompilationRequest cached = cachedRequestIfValid(compiler, request, primaryPath);
    if (cached != null) {
      logDecision("cache-hit", primaryPath, 0, 0, cached.sources.size(), true);
      return cached;
    }

    final LinkedHashMap<Path, JavaFileObject> expanded = new LinkedHashMap<>();
    putIfAbsent(expanded, primaryPath, primary);

    addSamePackageSources(compiler, module, primaryPath, expanded);
    final int samePackageAdded = expanded.size() - 1;
    addImportedSources(compiler, primaryPath, expanded);
    final int importedAdded = expanded.size() - 1 - samePackageAdded;
    if (expanded.size() == 1) {
      logDecision("no-related-sources", primaryPath, samePackageAdded, importedAdded, 1, false);
      cacheEntry =
          new WorkingSetCacheEntry(
              primaryPath,
              primary.getLastModified(),
              module.getSourceIndexVersion(),
              List.of(primaryPath));
      return request;
    }
 

    cacheEntry =
        new WorkingSetCacheEntry(
            primaryPath,
            primary.getLastModified(),
            module.getSourceIndexVersion(),
            new ArrayList<>(expanded.keySet()));
    logDecision("expanded", primaryPath, samePackageAdded, importedAdded, expanded.size(), false);

    return new CompilationRequest(
        expanded.values(),
        request.partialRequest,
        request.allowPartialReparse,
        request.allowWorkingSet,
        request.compilationTaskProcessor,
        request.configureContext);
  }

  @Nullable
  private CompilationRequest cachedRequestIfValid(
      @NonNull final JavaCompilerService compiler,
      @NonNull final CompilationRequest request,
      @NonNull final Path primaryPath) {
    if (cacheEntry == null || !cacheEntry.primaryPath.equals(primaryPath)) {
      return null;
    }

    final ModuleProject module = compiler.getModule();
    if (module == null || module.getSourceIndexVersion() != cacheEntry.moduleSourceIndexVersion) {
      return null;
    }

    final JavaFileObject primary = request.sources.iterator().next();
    if (primary.getLastModified() != cacheEntry.primaryLastModified) {
      return null;
    }

    final List<JavaFileObject> cachedSources = new ArrayList<>(cacheEntry.sourcePaths.size());
    for (Path path : cacheEntry.sourcePaths) {
      if (path.equals(primaryPath)) {
        cachedSources.add(primary);
      } else {
        cachedSources.add(snapshotSource(path));
      }
    }

    return new CompilationRequest(
        cachedSources,
        request.partialRequest,
        request.allowPartialReparse,
        request.allowWorkingSet,
        request.compilationTaskProcessor,
        request.configureContext);
  }

  private void logDecision(
      @NonNull final String outcome,
      @Nullable final Path primaryPath,
      final int samePackageAdded,
      final int importedAdded,
      final int totalSources,
      final boolean cacheHit) {
    if (!IdeLogConfig.shouldLogDebug()) {
      return;
    }
    LOG.debug(
        "JAVA_WORKING_SET outcome={} enabled={} cacheHit={} primary={} samePackageAdded={} importedAdded={} totalSources={}",
        outcome,
        JavaPreferences.INSTANCE.isJavaCompilationWorkingSetEnabled(),
        cacheHit,
        primaryPath,
        samePackageAdded,
        importedAdded,
        totalSources);
  }

  private void addSamePackageSources(
      @NonNull final JavaCompilerService compiler,
      @NonNull final ModuleProject module,
      @NonNull final Path primaryPath,
      @NonNull final LinkedHashMap<Path, JavaFileObject> expanded) {
    final String packageName = compiler.packageNameOrEmpty(primaryPath);
    final List<SourceClassTrie.SourceNode> samePackage = module.listClassesFromSourceDirs(packageName);

    int added = 0;
    for (SourceClassTrie.SourceNode node : samePackage) {
      if (expanded.size() >= MAX_TOTAL_SOURCES || added >= MAX_SAME_PACKAGE_SOURCES) {
        break;
      }

      final Path path = node.getFile();
      if (path == null || path.equals(primaryPath)) {
        continue;
      }

      if (putIfAbsent(expanded, path, snapshotSource(path))) {
        added++;
      }
    }
  }

  private void addImportedSources(
      @NonNull final JavaCompilerService compiler,
      @NonNull final Path primaryPath,
      @NonNull final LinkedHashMap<Path, JavaFileObject> expanded) {
    final ModuleProject module = compiler.getModule();
    if (module == null) {
      return;
    }

    final List<String> imports = compiler.readImportsForWorkingSet(primaryPath);

    int added = 0;
    for (String imported : imports) {
      if (expanded.size() >= MAX_TOTAL_SOURCES || added >= MAX_IMPORTED_SOURCES) {
        break;
      }

      if (imported == null || imported.isBlank()) {
        continue;
      }

      if (imported.endsWith(".*")) {
        added += addStarImportedSources(module, imported, primaryPath, expanded, added);
        continue;
      }

      final Path path = compiler.findTypeDeclaration(imported);
      if (path == null || path == CompilerProvider.NOT_FOUND || path.equals(primaryPath)) {
        continue;
      }

      if (putIfAbsent(expanded, path, snapshotSource(path))) {
        added++;
      }
    }
  }

  private int addStarImportedSources(
      @NonNull final ModuleProject module,
      @NonNull final String imported,
      @NonNull final Path primaryPath,
      @NonNull final LinkedHashMap<Path, JavaFileObject> expanded,
      final int alreadyAdded) {
    if (!imported.endsWith(".*")) {
      return 0;
    }

    final String packageName = imported.substring(0, imported.length() - 2);
    final List<SourceClassTrie.SourceNode> starImported = module.listClassesFromSourceDirs(packageName);

    int added = 0;
    for (SourceClassTrie.SourceNode node : starImported) {
      if (expanded.size() >= MAX_TOTAL_SOURCES || alreadyAdded + added >= MAX_IMPORTED_SOURCES) {
        break;
      }

      final Path path = node.getFile();
      if (path == null || path.equals(primaryPath)) {
        continue;
      }
      if (putIfAbsent(expanded, path, snapshotSource(path))) {
        added++;
      }
    }

    return added;
  }

  @NonNull
  private JavaFileObject snapshotSource(@NonNull final Path path) {
    return new SourceFileObject(
        path,
        FileManager.INSTANCE.getDocumentContents(path),
        FileManager.INSTANCE.getLastModified(path));
  }

  private boolean putIfAbsent(
      @NonNull final Map<Path, JavaFileObject> map,
      @NonNull final Path path,
      @NonNull final JavaFileObject source) {
    if (map.containsKey(path)) {
      return false;
    }

    map.put(path, source);
    return true;
  }

  @Nullable
  private Path sourcePathOf(@NonNull final JavaFileObject fileObject) {
    if (fileObject instanceof SourceFileObject) {
      return ((SourceFileObject) fileObject).path;
    }

    try {
      return Path.of(fileObject.toUri());
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static final class WorkingSetCacheEntry {
    @NonNull final Path primaryPath;
    final long primaryLastModified;
    final long moduleSourceIndexVersion;
    @NonNull final List<Path> sourcePaths;

    private WorkingSetCacheEntry(
        @NonNull final Path primaryPath,
        final long primaryLastModified,
        final long moduleSourceIndexVersion,
        @NonNull final List<Path> sourcePaths) {
      this.primaryPath = primaryPath;
      this.primaryLastModified = primaryLastModified;
      this.moduleSourceIndexVersion = moduleSourceIndexVersion;
      this.sourcePaths = sourcePaths;
    }
  }
}
