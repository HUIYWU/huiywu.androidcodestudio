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
import com.tom.rv2ide.common.logging.IdeLogConfig;
import com.tom.rv2ide.javac.services.compiler.ReusableCompiler;
import com.tom.rv2ide.lsp.java.kotlin.KotlinClassOutputProvider;
import com.tom.rv2ide.lsp.java.kotlin.KotlinJvmTypeIndex;
import com.tom.rv2ide.lsp.java.kotlin.KotlinSourceStubProvider;
import com.tom.rv2ide.lsp.java.models.CompilationRequest;
import com.tom.rv2ide.preferences.internal.JavaPreferences;
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
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
  private static final long RECREATE_COMPILER_MEMORY_THRESHOLD_BYTES = 160L * 1024L * 1024L;

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
    if (module != null && JavaPreferences.INSTANCE.isJavaKotlinRecognitionEnabled()) {
      all.addAll(KotlinJvmTypeIndex.publicTopLevelTypes(module));
      all.addAll(KotlinClassOutputProvider.publicDependencyTopLevelTypes(module));
    }
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
          final boolean needsCompilation = needsCompilation(request.sources);
          if (needsCompilation) {
            reparseOrRecompile(request);
          } else {
            if (IdeLogConfig.shouldLogDebug()) {
              LOG.debug("...using cached compile");
            }
          }
          synchronizedTask.setTask(
              new CompileTask(cachedCompile, diagnostics, false));
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

  /**
   * Recompiles the current request on the stable semantic path.
   *
   * <p>Legacy javac AST mutation is intentionally not routed here. Incremental analysis must first
   * establish a revision-based plan, an isolated analysis task, differential validation, and a
   * demonstrable latency benefit before it can become a request strategy.
   */
  private synchronized void reparseOrRecompile(CompilationRequest request) {
    recompile(request);
  }

  private synchronized void recompile(CompilationRequest request) {
    close();
    this.cachedCompile = performCompilation(request);
    updateModificationCache(request);
  }

  public synchronized void close() {
    final Runtime runtimeBeforeClose = Runtime.getRuntime();
    final long usedBeforeClose = runtimeBeforeClose.totalMemory() - runtimeBeforeClose.freeMemory();

    boolean recreatedReusableCompiler = false;
    if (cachedCompile != null) {
      cachedCompile.close();
      cachedCompile.borrow.close();
      cachedCompile = null;
    }
    final boolean shouldRecreateForMemoryPressure = usedBeforeClose >= RECREATE_COMPILER_MEMORY_THRESHOLD_BYTES;
    final boolean shouldRecreateCompiler =
        compiler != null && compiler.currentContext != null && shouldRecreateForMemoryPressure;
    if (shouldRecreateCompiler) {
      compiler = new ReusableCompiler();
      recreatedReusableCompiler = true;
    }
  }


  private void updateModificationCache(final CompilationRequest request) {
    cachedModified.clear();
    for (JavaFileObject f : request.sources) {
      cachedModified.put(f, f.getLastModified());
    }
  }
  private CompileBatch performCompilation(CompilationRequest request) {
    Collection<? extends JavaFileObject> sources = request.sources;
    // Kotlin stubs are part of the stable full-compilation input whenever Kotlin recognition is on.
    if (module != null && JavaPreferences.INSTANCE.isJavaKotlinRecognitionEnabled()) {
      final Collection<JavaFileObject> kotlinStubs = KotlinSourceStubProvider.stubsFor(module, sources);
      if (!kotlinStubs.isEmpty()) {
        final List<JavaFileObject> withKotlinStubs = new ArrayList<>(sources);
        withKotlinStubs.addAll(kotlinStubs);
        sources = withKotlinStubs;
      }
    }
    if (IdeLogConfig.shouldLogDebug()) {
      LOG.debug(
          "performCompilation sourcesDetail requestHash={} sourcesDetail={}",
          System.identityHashCode(request),
          sources == null ? null : CompileBatch.describeSources(sources));
    }

    if (sources.isEmpty()) {
      throw new RuntimeException("empty sources");
    }

    CompileBatch firstAttempt;
    try {
      firstAttempt = new CompileBatch(this, sources, request);
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
    final Set<String> sourceUris = new HashSet<>();
    for (JavaFileObject source : sources) {
      sourceUris.add(source.toUri().normalize().toString());
    }
    for (Path add : addFiles) {
      final SourceFileObject additionalSource = new SourceFileObject(add);
      if (sourceUris.add(additionalSource.toUri().normalize().toString())) {
        moreSources.add(additionalSource);
      }
    }
 
    return new CompileBatch(this, moreSources, request);
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
