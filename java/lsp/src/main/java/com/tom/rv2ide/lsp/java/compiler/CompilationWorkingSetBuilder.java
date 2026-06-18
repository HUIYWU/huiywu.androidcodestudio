package com.tom.rv2ide.lsp.java.compiler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.tom.rv2ide.common.logging.IdeLogConfig;
import com.tom.rv2ide.lsp.java.models.CompilationRequest;
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
      return request;
    }

    final Collection<JavaFileObject> originalSources = request.getSources();
    if (originalSources.isEmpty() || originalSources.size() != 1) {
      return request;
    }

    final JavaFileObject primary = originalSources.iterator().next();
    final Path primaryPath = sourcePathOf(primary);
    if (primaryPath == null) {
      return request;
    }
    final CompilationRequest cached = cachedRequestIfValid(request, primaryPath);
    if (cached != null) {
      return cached;
    }

    final LinkedHashMap<Path, JavaFileObject> expanded = new LinkedHashMap<>();
    putIfAbsent(expanded, primaryPath, primary);

    addSamePackageSources(compiler, module, primaryPath, expanded);
    addImportedSources(compiler, primaryPath, expanded);

    if (expanded.size() == 1) {
      cacheEntry =
          new WorkingSetCacheEntry(
              primaryPath,
              primary.getLastModified(),
              module.sourceIndexVersion(),
              List.of(primaryPath));
      return request;
    }


    if (IdeLogConfig.shouldLogIde()) {
      LOG.info(
          "Expanded Java compilation working set for file={} from {} to {} sources",
          primaryPath,
          originalSources.size(),
          expanded.size());
    }

    cacheEntry =
        new WorkingSetCacheEntry(
            primaryPath,
            primary.getLastModified(),
            module.sourceIndexVersion(),
            new ArrayList<>(expanded.keySet()));

    return new CompilationRequest(
        expanded.values(),
        request.getPartialRequest(),
        request.getCompilationTaskProcessor(),
        request.getConfigureContext());
  }

  @Nullable
  private CompilationRequest cachedRequestIfValid(
      @NonNull final CompilationRequest request, @NonNull final Path primaryPath) {
    if (cacheEntry == null || !cacheEntry.primaryPath.equals(primaryPath)) {
      return null;
    }

    final ModuleProject module = compiler.getModule();
    if (module == null || module.sourceIndexVersion() != cacheEntry.moduleSourceIndexVersion) {
      return null;
    }

    final JavaFileObject primary = request.getSources().iterator().next();
    if (primary.getLastModified() != cacheEntry.primaryLastModified) {
      return null;
    }

    final List<JavaFileObject> cachedSources = new ArrayList<>(cacheEntry.sourcePaths.size());
    for (Path path : cacheEntry.sourcePaths) {
      cachedSources.add(new SourceFileObject(path));
    }

    return new CompilationRequest(
        cachedSources,
        request.getPartialRequest(),
        request.getCompilationTaskProcessor(),
        request.getConfigureContext());
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

      if (putIfAbsent(expanded, path, new SourceFileObject(path))) {
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

      if (putIfAbsent(expanded, path, new SourceFileObject(path))) {
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

      if (putIfAbsent(expanded, path, new SourceFileObject(path))) {
        added++;
      }
    }

    return added;
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
