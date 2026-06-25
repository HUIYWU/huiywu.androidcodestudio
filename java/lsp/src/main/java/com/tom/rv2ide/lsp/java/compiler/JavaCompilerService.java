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

package com.tom.rv2ide.lsp.java.compiler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import com.tom.rv2ide.common.logging.IdeLogConfig;
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent;
import com.tom.rv2ide.javac.services.compiler.ReusableCompiler;
import com.tom.rv2ide.javac.services.partial.CompilationInfo;
import com.tom.rv2ide.javac.services.partial.PartialReparser;
import com.tom.rv2ide.javac.services.partial.PartialReparserImpl;
import com.tom.rv2ide.lsp.java.models.CompilationRequest;
import com.tom.rv2ide.lsp.java.models.PartialReparseRequest;
import com.tom.rv2ide.lsp.java.parser.ParseTask;
import com.tom.rv2ide.lsp.java.parser.Parser;
import com.tom.rv2ide.lsp.java.utils.Extractors;
import com.tom.rv2ide.lsp.java.visitors.FindTypeDeclarations;
import com.tom.rv2ide.models.Range;
import com.tom.rv2ide.projects.FileManager;
import com.tom.rv2ide.projects.ModuleProject;
import com.tom.rv2ide.projects.android.AndroidModule;
import com.tom.rv2ide.projects.java.JavaModule;

import com.tom.rv2ide.projects.util.BootClasspathProvider;
import com.tom.rv2ide.projects.util.StringSearch;
import com.tom.rv2ide.utils.Cache;
import com.tom.rv2ide.utils.Environment;
import com.tom.rv2ide.utils.SourceClassTrie;
import com.tom.rv2ide.utils.StopWatch;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jdkx.tools.Diagnostic;
import jdkx.tools.JavaFileObject;
import jdkx.tools.StandardLocation;
import openjdk.source.tree.CompilationUnitTree;
import openjdk.source.tree.MethodTree;
import openjdk.source.util.SourcePositions;
import openjdk.source.util.TreePath;
import openjdk.source.util.Trees;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaCompilerService implements CompilerProvider {

  public static final JavaCompilerService NO_MODULE_COMPILER = new JavaCompilerService(null);
  private static final Cache<String, Boolean> cacheContainsWord = new Cache<>();
  private static final Cache<Void, List<String>> cacheContainsType = new Cache<>();
  private static final Logger LOG = LoggerFactory.getLogger(JavaCompilerService.class);
  protected final Set<String> classPathClasses;
  protected final List<Diagnostic<? extends JavaFileObject>> diagnostics = new ArrayList<>();
  protected final Map<JavaFileObject, Long> cachedModified = new HashMap<>();
  protected final Cache<Void, List<String>> cacheFileImports = new Cache<>();
  protected final SynchronizedTask synchronizedTask = new SynchronizedTask();
  protected final SourceFileManager fileManager;
  protected final ModuleProject module;
  public ReusableCompiler compiler = new JCReusableCompiler();
  protected Set<String> bootClasspathClasses =
      BootClasspathProvider.getTopLevelClasses(
          Collections.singleton(Environment.ANDROID_JAR.getAbsolutePath()));
  private CompileBatch cachedCompile;
  private final PartialReparseDecider partialReparseDecider = new PartialReparseDecider();
  private final PartialReparseRouter partialReparseRouter = new PartialReparseRouter();
  private final PartialReparseFallbackHandler partialReparseFallbackHandler =
      new PartialReparseFallbackHandler();
  private final PartialReparsePreflight partialReparsePreflight = new PartialReparsePreflight();
  private final PartialReparseExecutor partialReparseExecutor = new PartialReparseExecutor();
  private final PartialReparseMethodLocator partialReparseMethodLocator = new PartialReparseMethodLocator();
  private final PartialReparseDryRunVerifier partialReparseDryRunVerifier = new PartialReparseDryRunVerifier();
  private final PartialReparseDryRunAttemptProvider partialReparseDryRunAttemptProvider =
      new PartialReparseDryRunAttemptProvider();

  private final PartialReparseDryRunSnapshotCollector partialReparseDryRunSnapshotCollector =
      new PartialReparseDryRunSnapshotCollector();
  private final PartialReparseDryRunPartialSnapshotProvider partialReparseDryRunPartialSnapshotProvider =
      new PartialReparseDryRunPartialSnapshotProvider();
  private final PartialReparseDryRunComparisonRunner partialReparseDryRunComparisonRunner =
      new PartialReparseDryRunComparisonRunner();
  private final JavaIncrementalState incrementalState = new JavaIncrementalState();
  private final CompilationWorkingSetBuilder compilationWorkingSetBuilder =
      new CompilationWorkingSetBuilder();

  // The module project must not be null
  // It is marked as nullable just for some special cases like tests
  public JavaCompilerService(@Nullable ModuleProject module) {
    this.module = module;
    if (module == null) {
      this.fileManager = SourceFileManager.NO_MODULE;
      this.classPathClasses = Collections.emptySet();
    } else {
      this.fileManager = SourceFileManager.forModule(module);
      this.classPathClasses =
          Collections.unmodifiableSet(module.compileClasspathClasses.allClassNames());
      this.bootClasspathClasses = Collections.unmodifiableSet(getBootclasspathClasses());
    }
  }
  private Set<String> getBootclasspathClasses() {
    final List<String> classpaths = new ArrayList<>();

    if (module instanceof AndroidModule) {
      classpaths.addAll(
          ((AndroidModule) module)
              .getBootClassPaths().stream().map(File::getPath).collect(Collectors.toList()));
    } else if (module instanceof JavaModule) {
      final JavaModule javaModule = (JavaModule) module;
      for (ModuleProject dependency : javaModule.getCompileModuleProjects()) {
        if (dependency instanceof AndroidModule) {
          classpaths.addAll(
              ((AndroidModule) dependency)
                  .getBootClassPaths().stream().map(File::getPath).collect(Collectors.toList()));
        }
      }

      if (classpaths.isEmpty()) {
        classpaths.addAll(
            javaModule.getInheritedBootClassPaths().stream()
                .filter(Objects::nonNull)
                .map(File::getPath)
                .distinct()
                .collect(Collectors.toList()));
      }

      if (classpaths.isEmpty()) {
        classpaths.addAll(
            module.getCompileClasspaths().stream()
                .filter(Objects::nonNull)
                .map(File::getPath)
                .filter(
                    path ->
                        path.endsWith("/android.jar")
                            || path.endsWith(File.separator + "android.jar"))
                .distinct()
                .collect(Collectors.toList()));
      }
    }

    final List<String> normalizedClasspaths =
        classpaths.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());

    if (normalizedClasspaths.isEmpty()) {
      return Collections.emptySet();
    }

    BootClasspathProvider.update(normalizedClasspaths);
    this.bootClasspathClasses =
        Collections.unmodifiableSet(BootClasspathProvider.getTopLevelClasses(normalizedClasspaths));
    return bootClasspathClasses;
  }


  private JavaCompilerService(
      @Nullable ModuleProject module,
      SourceFileManager fileManager,
      Set<String> bootClasspathClasses,
      Set<String> classPathClasses) {
    this.module = module;
    this.fileManager = fileManager;
    this.bootClasspathClasses = bootClasspathClasses;
    this.classPathClasses = classPathClasses;
  }

  public ModuleProject getModule() {
    return module;
  }
  @Override
  public TreeSet<String> publicTopLevelTypes() {
    TreeSet<String> all = new TreeSet<>();
    List<SourceClassTrie.SourceNode> sourceClasses =
        module != null ? module.compileJavaSourceClasses.allSources() : Collections.emptyList();
    for (SourceClassTrie.SourceNode node : sourceClasses) {
      all.add(node.getQualifiedName());
    }
    final int sourceCount = all.size();
    final int classPathCount = classPathClasses.size();
    final int bootClassPathCount = bootClasspathClasses.size();
    all.addAll(classPathClasses);
    all.addAll(bootClasspathClasses);
    if (IdeLogConfig.shouldLogDebug()) {
      LOG.debug(
          "publicTopLevelTypes source={} classpath={} boot={} total={} hasString={} hasInteger={} hasDouble={}",
          sourceCount,
          classPathCount,
          bootClassPathCount,
          all.size(),
          all.contains("java.lang.String"),
          all.contains("java.lang.Integer"),
          all.contains("java.lang.Double"));
    }
    return all;
  }


  @Override
  public TreeSet<String> packagePrivateTopLevelTypes(String packageName) {
    return new TreeSet<>();
  }

  @Override
  public Optional<JavaFileObject> findAnywhere(String className) {
    Path fromSource = findTypeDeclaration(className);
    if (fromSource != NOT_FOUND) {
      return Optional.of(new SourceFileObject(fromSource));
    }
    return Optional.empty();
  }

  @Override
  public Path findTypeDeclaration(String className) {
    Path fastFind = findPublicTypeDeclaration(className);
    if (fastFind != NOT_FOUND) {
      return fastFind;
    }
    // In principle, the slow path can be skipped in many cases.
    // If we're spending a lot of time in findTypeDeclaration, this would be a good
    // optimization.
    String packageName = Extractors.packageName(className);
    String simpleName = Extractors.simpleName(className);
    List<SourceClassTrie.SourceNode> classes =
        module != null ? module.listClassesFromSourceDirs(packageName) : Collections.emptyList();
    for (SourceClassTrie.SourceNode node : classes) {
      final Path path = node.getFile();
      if (containsWord(path, simpleName) && containsType(path, className)) {
        return path;
      }
    }
    return NOT_FOUND;
  }

  @Override
  public Path[] findTypeReferences(String className) {
    String packageName = Extractors.packageName(className);
    String simpleName = Extractors.simpleName(className);
    List<Path> candidates = new ArrayList<>();
    List<SourceClassTrie.SourceNode> sourceNodes =
        module != null ? module.compileJavaSourceClasses.allSources() : Collections.emptyList();
    for (SourceClassTrie.SourceNode node : sourceNodes) {
      final Path path = node.getFile();
      if (containsWord(path, packageName)
          && containsImport(path, className)
          && containsWord(path, simpleName)) {
        candidates.add(path);
      }
    }

    return candidates.toArray(new Path[0]);
  }

  @Override
  public Path[] findMemberReferences(String className, String memberName) {
    List<Path> candidates = new ArrayList<>();
    List<SourceClassTrie.SourceNode> sourceNodes =
        module != null ? module.compileJavaSourceClasses.allSources() : Collections.emptyList();
    for (SourceClassTrie.SourceNode node : sourceNodes) {
      final Path path = node.getFile();
      if (containsWord(path, memberName)) {
        candidates.add(path);
      }
    }
    return candidates.toArray(new Path[0]);
  }

  @Override
  public List<String> findQualifiedNames(String simpleName, boolean onlyOne) {
    final var names = new ArrayList<String>();
    for (var name : publicTopLevelTypes()) {
      // This will be true in a test environment
      if (name.contains("/")) {
        name = name.replace('/', '.');
      }

      if (name.endsWith("." + simpleName)) {
        names.add(name);
        if (onlyOne) {
          break;
        }
      }
    }
    return names;
  }

  @Override
  public ParseTask parse(Path file) {
    Parser parser = Parser.parseFile(file);
    return new ParseTask(parser.task, parser.root);
  }

  @Override
  public ParseTask parse(JavaFileObject file) {
    Parser parser = Parser.parseJavaFileObject(file);
    return new ParseTask(parser.task, parser.root);
  }

  @Override
  public SynchronizedTask compile(final CompilationRequest request) {
    return compileBatch(request);
  }

  private SynchronizedTask compileBatch(CompilationRequest request) {
    synchronizedTask.post(
        () -> {
          if (IdeLogConfig.shouldLogDebug()) {
            LOG.debug(
                "compileBatch start requestHash={} sources={} needsCompilation={} cachedCompilePresent={} currentContextPresent={}",
                System.identityHashCode(request),
                request.sources == null ? -1 : request.sources.size(),
                needsCompilation(request.sources),
                cachedCompile != null,
                compiler.currentContext != null);
          }
          if (needsCompilation(request.sources)) {
            reparseOrRecompile(request);
          } else {
            if (IdeLogConfig.shouldLogDebug()) {
              LOG.debug("...using cached compile");
            }
          }
          synchronizedTask.setTask(new CompileTask(cachedCompile, diagnostics));
        });

    return synchronizedTask;
  }

  private boolean needsCompilation(Collection<? extends JavaFileObject> sources) {
    if (cachedModified.size() != sources.size()) {
      return true;
    }
    for (JavaFileObject f : sources) {
      if (!cachedModified.containsKey(f)) {
        return true;
      }

      final Long modified = cachedModified.get(f);
      if (modified != null && f.getLastModified() != modified) {
        return true;
      }
    }
    return false;
  }

  private synchronized void reparseOrRecompile(CompilationRequest request) {
    final PartialReparseEligibility eligibility =
        PartialReparseEligibility.from(request, needsRecompilation(request), incrementalState);
    final PartialReparseDecision decision = partialReparseDecider.decide(eligibility);
    logPartialReparseDecision(eligibility, decision);
    if (IdeLogConfig.shouldLogDebug()) {
      LOG.debug(
          "reparseOrRecompile requestHash={} action={} sourceCount={} needsRecompilation={} currentContextPresent={}",
          System.identityHashCode(request),
          decision.action,
          eligibility.sourceCount,
          eligibility.needsRecompilation,
          compiler.currentContext != null);
    }

    partialReparseRouter.route(
        decision,
        () -> recompile(request),
        () -> dryRunPartialReparseThenFullRecompile(request, eligibility),
        () -> tryPartialReparseWithFallback(request));
  }

  private void logPartialReparseDecision(
      @NonNull final PartialReparseEligibility eligibility,
      @NonNull final PartialReparseDecision decision) {
    if (!JavaLspFeatureFlags.ENABLE_PARTIAL_REPARSE_LOGGING || !IdeLogConfig.shouldLogDebug()) {
      return;
    }

    LOG.debug(
        "Partial reparse decision: action={} reason={} needsRecompilation={} changeValid={} changeDeltaWithinLimit={} maxChangeDelta={} sources={} hasPartialRequest={} hasLatestChangeRange={} cursor={} contentsLength={} changeDelta={} newCursorPosition={}",
        decision.action,
        decision.reason,
        eligibility.needsRecompilation,
        eligibility.changeValidForReparse,
        eligibility.changeDeltaWithinLimit,
        JavaLspFeatureFlags.MAX_PARTIAL_REPARSE_CHANGE_DELTA,
        eligibility.sourceCount,
        eligibility.hasPartialRequest,
        eligibility.latestChangeRange != null,
        eligibility.cursor,
        eligibility.contentsLength,
        eligibility.changeDelta,
        eligibility.newCursorPosition);
  }

  private void dryRunPartialReparseThenFullRecompile(
      @NonNull final CompilationRequest request, @NonNull final PartialReparseEligibility eligibility) {
    // Keep the user-visible result on the stable full-recompile path. The default attempt provider
    // returns null until a truly isolated copy/snapshot execution path exists; the verifier keeps
    // this branch explicit and testable.
    final PartialReparseDryRunSnapshot[] fullSnapshot = new PartialReparseDryRunSnapshot[1];
    final PartialReparseDryRunReport report =
        partialReparseDryRunVerifier.verifyThenFullRecompile(
            partialReparseDryRunAttemptProvider.createAttempt(request, eligibility),
            () -> {
              recompile(request);
              fullSnapshot[0] = collectDryRunFullRecompileSnapshot();
            },
            (result, err) -> logPartialReparseDryRun(result, err));
    final PartialReparseDryRunReport reportWithComparison =
        partialReparseDryRunComparisonRunner.attachComparison(
            report,
            fullSnapshot[0],
            attemptReport ->
                      partialReparseDryRunPartialSnapshotProvider.createPartialSnapshot(
                          request, eligibility, attemptReport, this));
    logPartialReparseDryRunReport(reportWithComparison);
  }
  @Nullable
  private PartialReparseDryRunSnapshot collectDryRunFullRecompileSnapshot() {
    if (cachedCompile == null) {
      return null;
    }
    return partialReparseDryRunSnapshotCollector.collect(diagnostics, cachedCompile.methodPositions);
  }

  private void logPartialReparseDryRun(
      @Nullable final PartialReparseAttemptResult result, @Nullable final Throwable err) {

    if (!JavaLspFeatureFlags.ENABLE_PARTIAL_REPARSE_LOGGING || !IdeLogConfig.shouldLogDebug()) {
      return;
    }
    if (err != null) {
      LOG.debug("Partial reparse dry-run attempt failed before full recompile", err);
      return;
    }
    if (result != null) {
      LOG.debug("Partial reparse dry-run attempt result: status={} reason={}", result.status, result.reason);
      return;
    }
    LOG.debug("Partial reparse dry-run requested; using full recompile until isolated dry-run is available");

  }

  private void logPartialReparseDryRunReport(@NonNull final PartialReparseDryRunReport report) {
    if (!JavaLspFeatureFlags.ENABLE_PARTIAL_REPARSE_LOGGING || !IdeLogConfig.shouldLogDebug()) {
      return;
    }
    LOG.debug(
        "Partial reparse dry-run report: attemptState={} reason={} fullRecompileExecuted={} "
            + "partialResultCommitted={} diagnosticsComparison={} methodPositionsComparison={} "
            + "sourcePositionsComparison={} comparisonReason={}",
        report.attemptState,
        report.reason,
        report.fullRecompileExecuted,
        report.partialResultCommitted,
        report.comparison.diagnosticsComparison,
        report.comparison.methodPositionsComparison,
        report.comparison.sourcePositionsComparison,
        report.comparison.reason);
  }

  private void tryPartialReparseWithFallback(@NonNull final CompilationRequest request) {
    partialReparseFallbackHandler.handle(
        () -> tryReparse(request),
        () -> recompile(request),
        (result, err) -> logPartialReparseFallback(result, err));
  }

  private void logPartialReparseFallback(
      @Nullable final PartialReparseAttemptResult result, @Nullable final Throwable err) {
    if (!JavaLspFeatureFlags.ENABLE_PARTIAL_REPARSE_LOGGING || !IdeLogConfig.shouldLogWarn()) {
      return;
    }
    if (err != null) {
      LOG.warn("Partial reparse failed. Falling back to full recompile", err);
      return;
    }
    if (result != null) {
      LOG.warn(
          "Partial reparse did not produce a reusable result. status={} reason={}. Falling back to full recompile",
          result.status,
          result.reason);
    }
  }

  private boolean needsRecompilation(final CompilationRequest request) {
    return this.cachedCompile == null
        || this.cachedCompile.closed;
  }

  private PartialReparseAttemptResult tryReparse(@NonNull final CompilationRequest request) {

    // Satisfy lint
    final PartialReparseRequest partialRequest = request.partialRequest;
    Objects.requireNonNull(partialRequest);

    final StopWatch watch = new StopWatch("Method reparse");
    final File file = new File(request.sources.iterator().next().toUri());
    final String path = file.getAbsolutePath();
    final List<Pair<Range, TreePath>> positions = this.cachedCompile.methodPositions.get(path);
    if (positions == null) {
      if (IdeLogConfig.shouldLogWarn()) {
        LOG.warn("Cannot perform reparse. No method positions found.");
      }
      return PartialReparseAttemptResult.notApplicable("method positions not found");
    }

    final Pair<Range, TreePath> currentMethod =
        partialReparseMethodLocator.findCurrentMethod(positions, partialRequest.cursor);
    final PartialReparseAttemptResult currentMethodPreflight =
        partialReparsePreflight.validateCurrentMethod(currentMethod, partialRequest.cursor);
    if (currentMethodPreflight != null) {
      if (IdeLogConfig.shouldLogWarn()) {
        LOG.warn("Cannot perform reparse. {}", currentMethodPreflight.reason);
      }
      return currentMethodPreflight;
    }
    if (IdeLogConfig.shouldLogDebug()) {
      watch.lapFromLast("Found method at cursor position");
    }

    final MethodTree methodTree = (MethodTree) currentMethod.second.getLeaf();
    final PartialReparseAttemptResult methodTreePreflight =
        partialReparsePreflight.validateMethodTree(methodTree);
    if (methodTreePreflight != null) {
      if (IdeLogConfig.shouldLogWarn()) {
        LOG.warn("Cannot perform reparse. {}", methodTreePreflight.reason);
      }
      return methodTreePreflight;
    }

    if (IdeLogConfig.shouldLogDebug()) {
      LOG.debug("Trying to reparse method: {}", methodTree.getName());
    }

    final CompilationInfo info =
        new CompilationInfo(
            cachedCompile.task, cachedCompile.diagnosticListener, cachedCompile.roots.get(0));
    watch.setLastLap(System.currentTimeMillis());
    final SourcePositions sourcePositions = Trees.instance(cachedCompile.task).getSourcePositions();
    final int start = (int) sourcePositions.getStartPosition(info.cu, methodTree.getBody());
    final int end =
        (int) sourcePositions.getEndPosition(info.cu, methodTree.getBody())
            + this.incrementalState.getChangeDelta();

    final PartialReparseAttemptResult rangePreflight =
        partialReparsePreflight.validateRanges(
            this.incrementalState.getLatestChangeRange(),
            start,
            end,
            partialRequest.contents.length());
    if (rangePreflight != null) {
      if (IdeLogConfig.shouldLogWarn()) {
        if (rangePreflight.status == PartialReparseAttemptResult.Status.FAILED) {
          LOG.warn(
              "Cannot reparse. {}. end: {} changeDelta: {} content.length: {}",
              rangePreflight.reason,
              end,
              this.incrementalState.getChangeDelta(),
              partialRequest.contents.length());
        } else {
          LOG.warn(
              "Cannot reparse. {}. methodBodyStart: {} methodBodyEnd: {} changeRange: {}",
              rangePreflight.reason,
              start,
              end,
              this.incrementalState.getLatestChangeRange());
        }
      }
      return rangePreflight;
    }

    if (IdeLogConfig.shouldLogDebug()) {
      watch.lapFromLast("Found start and end positions of current method");
    }
    final PartialReparser reparser = new PartialReparserImpl();
    final PartialReparseAttemptResult executeResult =
        partialReparseExecutor.execute(
            partialRequest.contents,
            start,
            end,
            newBody -> reparser.reparseMethod(info, currentMethod.second, newBody, partialRequest.contents));
    if (!executeResult.isSuccess()) {
      if (IdeLogConfig.shouldLogWarn()) {
        LOG.warn("Failed to reparse method body; falling back to full recompile. {}", executeResult.reason);
      }
      return executeResult;
    }

    if (IdeLogConfig.shouldLogDebug()) {
      watch.log();
      LOG.debug("Successfully reparsed method: {}", methodTree.getName());
    }
    updateModificationCache(request);
    cachedCompile.updatePositions(info.cu, true);
    this.incrementalState.markReparseSucceeded();
    return PartialReparseAttemptResult.success("method body reparsed");
  }

  private synchronized void recompile(CompilationRequest request) {
    if (IdeLogConfig.shouldLogDebug()) {
      LOG.debug(
          "recompile start requestHash={} sources={} cachedCompilePresentBeforeClose={} currentContextPresentBeforeClose={}",
          System.identityHashCode(request),
          request.sources == null ? -1 : request.sources.size(),
          cachedCompile != null,
          compiler.currentContext != null);
    }
    close();
    this.cachedCompile = performCompilation(request);
    this.incrementalState.resetAfterFullRecompile();
    updateModificationCache(request);
  }

  public synchronized void close() {
    if (cachedCompile != null) {
      cachedCompile.close();
      cachedCompile.borrow.close();
    }
  }

  private void updateModificationCache(final CompilationRequest request) {
    cachedModified.clear();
    for (JavaFileObject f : request.sources) {
      cachedModified.put(f, f.getLastModified());
    }
  }
  private CompileBatch performCompilation(CompilationRequest request) {
    final CompilationRequest expandedRequest =
        compilationWorkingSetBuilder.expand(this, request);
    final Collection<? extends JavaFileObject> sources = expandedRequest.sources;
    if (IdeLogConfig.shouldLogDebug()) {
      LOG.debug(
          "performCompilation requestHash={} expandedSources={} currentContextPresent={} fileManagerClass={} sourcesDetail={}",
          System.identityHashCode(request),
          sources == null ? -1 : sources.size(),
          compiler.currentContext != null,
          fileManager == null ? null : fileManager.getClass().getName(),
          sources == null ? null : CompileBatch.describeSources(sources));
    }
    if (sources.isEmpty()) {
      throw new RuntimeException("empty sources");
    }

    CompileBatch firstAttempt;
    try {
      firstAttempt = new CompileBatch(this, sources, expandedRequest);
    } catch (Throwable err) {
      LOG.error(
          "performCompilation failed requestHash={} expandedSources={} currentContextPresent={} firstSource={} ",
          System.identityHashCode(request),
          sources.size(),
          compiler.currentContext != null,
          sources.iterator().hasNext() ? sources.iterator().next().toUri() : null,
          err);
      throw err;
    }
    Set<Path> addFiles = firstAttempt.needsAdditionalSources();


    if (addFiles.isEmpty()) {
      return firstAttempt;
    }

    // If the compiler needs additional source files that contain package-private files
    firstAttempt.close();
    firstAttempt.borrow.close();

    List<JavaFileObject> moreSources = new ArrayList<>(sources);
    for (Path add : addFiles) {
      moreSources.add(new SourceFileObject(add));
    }

    return new CompileBatch(this, moreSources, expandedRequest);
  }

  @NonNull
  List<String> readImportsForWorkingSet(@NonNull Path file) {
    return readImports(file);
  }

  private boolean containsWord(Path file, String word) {
    if (cacheContainsWord.needs(file, word)) {
      cacheContainsWord.load(file, word, StringSearch.containsWord(file, word));
    }
    return cacheContainsWord.get(file, word);
  }

  private boolean containsImport(Path file, String className) {
    String packageName = Extractors.packageName(className);
    if (packageNameOrEmpty(file).equals(packageName)) {
      return true;
    }
    String star = packageName + ".*";
    for (String i : readImports(file)) {
      if (i.equals(className) || i.equals(star)) {
        return true;
      }
    }
    return false;
  }

  private List<String> readImports(Path file) {
    if (cacheFileImports.needs(file, null)) {
      loadImports(file);
    }
    return cacheFileImports.get(file, null);
  }

  private void loadImports(Path file) {
    List<String> list = new ArrayList<>();
    Pattern importClass = Pattern.compile("^import +([\\w.]+\\.\\w+);");
    Pattern importStar = Pattern.compile("^import +([\\w.]+\\.\\*);");
    try (BufferedReader lines = FileManager.INSTANCE.getReader(file)) {
      for (String line = lines.readLine(); line != null; line = lines.readLine()) {
        // If we reach a class declaration, stop looking for imports
        // TODO This could be a little more specific
        if (line.contains("class")) {
          break;
        }
        // import foo.bar.Doh;
        Matcher matchesClass = importClass.matcher(line);
        if (matchesClass.matches()) {
          list.add(matchesClass.group(1));
        }
        // import foo.bar.*
        Matcher matchesStar = importStar.matcher(line);
        if (matchesStar.matches()) {
          list.add(matchesStar.group(1));
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    cacheFileImports.load(file, null, list);
  }

  String packageNameOrEmpty(Path file) {
    return module != null ? module.packageNameOrEmpty(file) : "";
  }

  public void destroy() {
    synchronizedTask.post(
        () -> {
          close();
          cachedCompile = null;
          cachedModified.clear();
          compiler = new ReusableCompiler();
        });
  }

  public SynchronizedTask getSynchronizedTask() {
    return synchronizedTask;
  }

  public void onDocumentChange(@NonNull DocumentChangeEvent event) {
    this.incrementalState.onDocumentChange(event);
  }

  public JavaCompilerService copy() {
    final JavaCompilerService compiler =
        new JavaCompilerService(
            this.module, this.fileManager, this.bootClasspathClasses, this.classPathClasses);
    compiler.cachedCompile = null;
    compiler.incrementalState.resetForCopy();
    compiler.compiler = new ReusableCompiler();
    compiler.diagnostics.clear();
    compiler.cachedModified.clear();
    return compiler;
  }

  private boolean containsType(Path file, String className) {
    if (cacheContainsType.needs(file, null)) {
      CompilationUnitTree root = parse(file).root;
      List<String> types = new ArrayList<>();
      new FindTypeDeclarations().scan(root, types);
      cacheContainsType.load(file, null, types);
    }
    return cacheContainsType.get(file, null).contains(className);
  }

  private Path findPublicTypeDeclaration(String className) {
    JavaFileObject source;
    try {
      source =
          fileManager.getJavaFileForInput(
              StandardLocation.SOURCE_PATH, className, JavaFileObject.Kind.SOURCE);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    if (source == null) {
      return NOT_FOUND;
    }
    if (!source.toUri().getScheme().equals("file")) {
      return NOT_FOUND;
    }
    Path file = Paths.get(source.toUri());
    if (!containsType(file, className)) {
      return NOT_FOUND;
    }
    return file;
  }
}
