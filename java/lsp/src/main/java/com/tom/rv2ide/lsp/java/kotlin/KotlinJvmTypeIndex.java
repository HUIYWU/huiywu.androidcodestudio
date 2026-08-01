/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.java.kotlin;

import com.tom.rv2ide.projects.FileManager;
import com.tom.rv2ide.projects.ModuleProject;
import com.tom.rv2ide.preferences.internal.JavaPreferences;
import com.tom.rv2ide.utils.DocumentUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A lightweight Kotlin-as-Java type index used by the Java language server.
 *
 * <p>This deliberately does not expose Kotlin files through javac's {@code SOURCE_PATH}. javac
 * cannot parse Kotlin source. It only supplies JVM-visible top-level names to Java completion and
 * import-oriented features. A future ABI/stub provider can replace this scanner without changing
 * those consumers.
 */
public final class KotlinJvmTypeIndex {

  private static final Logger LOG = LoggerFactory.getLogger(KotlinJvmTypeIndex.class);
  private static final ConcurrentHashMap<ModuleProject, CachedTypes> CACHE =
      new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<ModuleProject, CachedAliases> ALIAS_CACHE =
      new ConcurrentHashMap<>();

  private static final Pattern PACKAGE_PATTERN =
      Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)");
  private static final Pattern FILE_JVM_NAME_PATTERN =
      Pattern.compile("(?m)^\\s*@file:JvmName\\s*\\(\\s*\\\"([A-Za-z_$][\\w$]*)\\\"\\s*\\)");
  private static final Pattern FILE_JVM_MULTIFILE_PATTERN =
      Pattern.compile("(?m)^\\s*@file:JvmMultifileClass(?:\\s|$)");
  private static final Pattern IMPORT_PATTERN = Pattern.compile(
      "(?m)^\\s*import\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)(?:\\s+as\\s+[A-Za-z_$][\\w$]*)?\\s*$");
  private static final Pattern TYPE_ALIAS_PATTERN = Pattern.compile(
      "(?m)^\\s*((?:(?:public|internal|private)\\s+)*)typealias\\s+([A-Za-z_][\\w]*)\\s*=\\s*([^\\r\\n]+?)\\s*$");
  private static final Pattern GENERIC_TYPE_ALIAS_PATTERN = Pattern.compile(
      "(?m)^\\s*((?:(?:public|internal|private)\\s+)*)typealias\\s+([A-Za-z_][\\w]*)\\s*<([^<>]+)>\\s*=\\s*([^\\r\\n]+?)\\s*$");
  private static final Pattern TYPE_NAME_PATTERN =
      Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*");

  private static final Pattern TYPE_PATTERN =
      Pattern.compile(
          "^\\s*((?:(?:public|protected|internal|private|open|abstract|sealed|data|enum|"
              + "annotation|value|expect|actual)\\s+)*)"
              + "(?:class|interface|object)\\s+([A-Za-z_][\\w]*)");
  private static final Pattern TOP_LEVEL_MEMBER_PATTERN =
      Pattern.compile("^\\s*(?:(?:public|private|protected|internal|inline|suspend|const|lateinit|"
          + "tailrec|operator|infix|external|override)\\s+)*(?:fun|val|var)\\s+");

  private KotlinJvmTypeIndex() {}

  /** Returns Kotlin declarations which Java can use as top-level class names or file facades. */
  public static Set<String> publicTopLevelTypes(ModuleProject module) {
    if (!isRecognitionEnabled() || module == null) {
      return Collections.emptySet();
    }

    final long revision = module.getSourceIndexVersion();
    final CachedTypes cached = CACHE.get(module);
    if (cached != null && cached.revision == revision) {
      return cached.types;
    }

    final Set<String> indexed = new LinkedHashSet<>();
    for (java.io.File root : module.getCompileSourceDirectories()) {
      if (root == null || !root.isDirectory()) {
        continue;
      }
      try (Stream<Path> paths = Files.walk(root.toPath())) {
        paths.filter(DocumentUtils::isKotlinFile)
            .filter(path -> path.getFileName().toString().endsWith(".kt"))
            .forEach(path -> indexFile(path, indexed));
      } catch (IOException error) {
        LOG.debug("Unable to scan Kotlin source root {}", root, error);
      }
    }

    final Set<String> immutable = Collections.unmodifiableSet(indexed);
    CACHE.put(module, new CachedTypes(revision, immutable));
    LOG.debug("Indexed {} Kotlin JVM top-level types for module {}", immutable.size(), module.getPath());
    return immutable;
  }

  /**
   * Finds the source declaration for a JVM-visible Kotlin top-level type or file facade.
   * This is intentionally used only by explicit navigation requests; completion continues to use
   * the cached name-only index above.
   */
  public static List<KotlinTypeDeclaration> findMultifileDeclarations(
      ModuleProject module, String qualifiedName) {
    if (!isRecognitionEnabled() || module == null || qualifiedName == null || qualifiedName.isEmpty()) {
      return Collections.emptyList();
    }
    final java.util.ArrayList<KotlinTypeDeclaration> result = new java.util.ArrayList<>();
    for (java.io.File root : module.getCompileSourceDirectories()) {
      if (root == null || !root.isDirectory()) {
        continue;
      }
      try (Stream<Path> paths = Files.walk(root.toPath())) {
        final java.util.Iterator<Path> iterator = paths
            .filter(DocumentUtils::isKotlinFile)
            .filter(path -> path.getFileName().toString().endsWith(".kt"))
            .iterator();
        while (iterator.hasNext()) {
          final Path path = iterator.next();
          final String source = FileManager.INSTANCE.getDocumentContents(path).toString();
          if (isMultifileFacade(source, path, qualifiedName)) {
            result.add(new KotlinTypeDeclaration(path, fileJvmNameOffset(source), facadeName(source, path).length()));
          }
        }
      } catch (IOException error) {
        LOG.debug("Unable to scan Kotlin multifile sources for {}", qualifiedName, error);
      }
    }
    return result.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(result);
  }

  public static java.util.Map<String, GenericTypeAlias> visibleGenericTypeAliases(
      ModuleProject module, Path consumerFile) {
    if (!isRecognitionEnabled() || module == null || consumerFile == null) {
      return Collections.emptyMap();
    }
    final String consumerSource = FileManager.INSTANCE.getDocumentContents(consumerFile).toString();
    final CachedAliases cached = aliasCacheForRevision(module);
    final java.util.Map<String, GenericTypeAlias> existing =
        cached.genericAliases.get(consumerFile, consumerSource);
    if (existing != null) {
      return existing;
    }
    final java.util.Map<String, GenericTypeAlias> indexed =
        aliasDeclarationIndexForRevision(module).visibleGenericAliases(consumerFile, consumerSource);
    cached.genericAliases.put(consumerFile, consumerSource, indexed);
    return indexed;
  }

  static java.util.Map<String, GenericTypeAlias> visibleGenericTypeAliases(
      Iterable<java.io.File> sourceRoots, Path consumerFile) {
    if (sourceRoots == null || consumerFile == null) {
      return Collections.emptyMap();
    }
    final String consumerSource = FileManager.INSTANCE.getDocumentContents(consumerFile).toString();
    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(consumerSource);
    final String consumerPackage = packageMatcher.find() ? packageMatcher.group(1) : "";
    final Set<String> explicitlyImported = new LinkedHashSet<>();
    final Matcher importMatcher = IMPORT_PATTERN.matcher(consumerSource);
    while (importMatcher.find()) explicitlyImported.add(importMatcher.group(1));
    final java.util.Map<String, GenericTypeAlias> aliases = new java.util.LinkedHashMap<>();
    for (java.io.File root : sourceRoots) {
      if (root == null || !root.isDirectory()) continue;
      try (Stream<Path> paths = Files.walk(root.toPath())) {
        paths.filter(DocumentUtils::isKotlinFile).filter(path -> !path.equals(consumerFile))
            .forEach(path -> collectVisibleGenericAliases(
                path, consumerPackage, explicitlyImported, aliases));
      } catch (IOException error) {
        LOG.debug("Unable to scan Kotlin generic typealiases for {}", consumerFile, error);
      }
    }
    return aliases.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(aliases);
  }

  public static java.util.Map<String, String> visibleDirectTypeAliases(
      ModuleProject module, Path consumerFile) {
    if (!isRecognitionEnabled() || module == null || consumerFile == null) {
      return Collections.emptyMap();
    }
    final String consumerSource = FileManager.INSTANCE.getDocumentContents(consumerFile).toString();
    final CachedAliases cached = aliasCacheForRevision(module);
    final java.util.Map<String, String> existing =
        cached.directAliases.get(consumerFile, consumerSource);
    if (existing != null) {
      return existing;
    }
    final java.util.Map<String, String> indexed =
        aliasDeclarationIndexForRevision(module).visibleDirectAliases(consumerFile, consumerSource);
    cached.directAliases.put(consumerFile, consumerSource, indexed);
    return indexed;
  }

  static java.util.Map<String, String> visibleDirectTypeAliases(
      Iterable<java.io.File> sourceRoots, Path consumerFile, String consumerSource) {
    if (sourceRoots == null || consumerFile == null || consumerSource == null) {
      return Collections.emptyMap();
    }
    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(consumerSource);
    final String consumerPackage = packageMatcher.find() ? packageMatcher.group(1) : "";
    final Set<String> explicitlyImported = new LinkedHashSet<>();
    final Matcher importMatcher = IMPORT_PATTERN.matcher(consumerSource);
    while (importMatcher.find()) {
      explicitlyImported.add(importMatcher.group(1));
    }
    final java.util.Map<String, String> aliases = new java.util.LinkedHashMap<>();
    for (java.io.File root : sourceRoots) {
      if (root == null || !root.isDirectory()) continue;
      try (Stream<Path> paths = Files.walk(root.toPath())) {
        paths.filter(DocumentUtils::isKotlinFile).filter(path -> !path.equals(consumerFile))
            .forEach(path -> collectVisibleAliases(path, consumerPackage, explicitlyImported, aliases));
      } catch (IOException error) {
        LOG.debug("Unable to scan Kotlin typealiases for {}", consumerFile, error);
      }
    }
    aliases.values().removeIf(aliases::containsKey);
    return aliases.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(aliases);
  }

  public static KotlinTypeDeclaration findDeclaration(ModuleProject module, String qualifiedName) {
    if (!isRecognitionEnabled() || module == null || qualifiedName == null || qualifiedName.isEmpty()) {
      return null;
    }
    for (java.io.File root : module.getCompileSourceDirectories()) {
      if (root == null || !root.isDirectory()) {
        continue;
      }
      try (Stream<Path> paths = Files.walk(root.toPath())) {
        final java.util.Iterator<Path> iterator = paths
            .filter(DocumentUtils::isKotlinFile)
            .filter(path -> path.getFileName().toString().endsWith(".kt"))
            .iterator();
        while (iterator.hasNext()) {
          final KotlinTypeDeclaration declaration = findDeclarationInFile(iterator.next(), qualifiedName);
          if (declaration != null) {
            return declaration;
          }
        }
      } catch (IOException error) {
        LOG.debug("Unable to scan Kotlin source root {} for {}", root, qualifiedName, error);
      }
    }
    return null;
  }

  /** Kotlin source discovery is opt-in; callers must not create scanner/cache work while disabled. */
  private static boolean isRecognitionEnabled() {
    return JavaPreferences.INSTANCE.isJavaKotlinRecognitionEnabled();
  }

  private static AliasDeclarationIndex aliasDeclarationIndexForRevision(ModuleProject module) {
    final long revision = module.getSourceIndexVersion();
    final CachedAliases cached = ALIAS_CACHE.get(module);
    if (cached != null && cached.revision == revision && cached.declarationIndex != null) {
      return cached.declarationIndex;
    }
    final AliasDeclarationIndex indexed = AliasDeclarationIndex.build(module.getCompileSourceDirectories());
    final CachedAliases replacement = new CachedAliases(revision, indexed);
    if (cached == null || cached.revision != revision) {
      ALIAS_CACHE.put(module, replacement);
      return indexed;
    }
    cached.declarationIndex = indexed;
    return indexed;
  }

  private static CachedAliases aliasCacheForRevision(ModuleProject module) {
    final long revision = module.getSourceIndexVersion();
    final CachedAliases existing = ALIAS_CACHE.get(module);
    if (existing != null && existing.revision == revision) {
      return existing;
    }
    final CachedAliases replacement = new CachedAliases(revision);
    ALIAS_CACHE.put(module, replacement);
    return replacement;
  }

  /** Removes cached Kotlin source symbols and alias visibility results for a module. */
  public static void invalidate(ModuleProject module) {
    if (module != null) {
      CACHE.remove(module);
      ALIAS_CACHE.remove(module);
    }
  }

  public static void clear() {
    CACHE.clear();
    ALIAS_CACHE.clear();
  }

  private static void indexFile(Path path, Set<String> result) {
    final String source = FileManager.INSTANCE.getDocumentContents(path).toString();

    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
    final String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
    final List<KotlinJvmSyntaxParser.TopLevelTypeSyntax> syntaxTypes =
        KotlinJvmSyntaxParser.findTopLevelTypes(source);
    final List<KotlinJvmSyntaxParser.MemberSyntax> syntaxMembers =
        KotlinJvmSyntaxParser.findTopLevelMembers(source);
    final boolean hasTopLevelMember;

    if (syntaxTypes != null && syntaxMembers != null) {
      for (KotlinJvmSyntaxParser.TopLevelTypeSyntax type : syntaxTypes) {
        if (!type.privateType && type.name != null && !type.name.isEmpty()) {
          result.add(qualifiedName(packageName, type.name));
        }
      }
      hasTopLevelMember = hasPublicTopLevelMember(syntaxMembers);
    } else {
      // Retain the previous scanner only for devices where the native grammar is unavailable.
      hasTopLevelMember = indexFileFallback(source, packageName, result);
    }

    if (hasTopLevelMember) {
      final Matcher jvmNameMatcher = FILE_JVM_NAME_PATTERN.matcher(source);
      final String facade = jvmNameMatcher.find()
          ? jvmNameMatcher.group(1)
          : path.getFileName().toString().substring(0, path.getFileName().toString().length() - 3) + "Kt";
      result.add(qualifiedName(packageName, facade));
    }
  }

  private static void collectVisibleGenericAliases(
      Path path,
      String consumerPackage,
      Set<String> explicitlyImported,
      java.util.Map<String, GenericTypeAlias> result) {
    final String source = FileManager.INSTANCE.getDocumentContents(path).toString();
    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
    final String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
    final Matcher matcher = GENERIC_TYPE_ALIAS_PATTERN.matcher(source);
    while (matcher.find()) {
      final String modifiers = matcher.group(1);
      final String name = matcher.group(2);
      final String qualifiedAlias = qualifiedName(packageName, name);
      final String target = matcher.group(4).trim();
      if (modifiers.contains("private") || modifiers.contains("internal")
          || result.containsKey(name)
          || !(packageName.equals(consumerPackage) || explicitlyImported.contains(qualifiedAlias))
          || target.endsWith("?") || target.indexOf("->") >= 0 || target.indexOf('&') >= 0
          || target.indexOf('|') >= 0) {
        continue;
      }
      final java.util.ArrayList<String> parameters = new java.util.ArrayList<>();
      boolean valid = true;
      for (String parameter : KotlinJvmTypeProjection.splitTopLevelArguments(matcher.group(3))) {
        final String trimmed = parameter.trim();
        if (!TYPE_NAME_PATTERN.matcher(trimmed).matches()) { valid = false; break; }
        parameters.add(trimmed);
      }
      final int open = target.indexOf('<');
      if (!valid || parameters.isEmpty() || open < 1 || !target.endsWith(">")) continue;
      final String raw = target.substring(0, open).trim();
      if (raw.indexOf('.') >= 0) continue;
      final java.util.ArrayList<String> arguments = new java.util.ArrayList<>();
      for (String argument : KotlinJvmTypeProjection.splitTopLevelArguments(
          target.substring(open + 1, target.length() - 1))) {
        final String trimmed = argument.trim();
        if (!TYPE_NAME_PATTERN.matcher(trimmed).matches()) { valid = false; break; }
        arguments.add(trimmed);
      }
      if (valid) result.put(name, new GenericTypeAlias(parameters, raw, arguments));
    }
  }

  // Generic argument splitting is shared with generation and navigation.

  private static void collectVisibleAliases(
      Path path,
      String consumerPackage,
      Set<String> explicitlyImported,
      java.util.Map<String, String> result) {
    final String source = FileManager.INSTANCE.getDocumentContents(path).toString();
    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
    final String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
    final Matcher aliasMatcher = TYPE_ALIAS_PATTERN.matcher(source);
    while (aliasMatcher.find()) {
      final String modifiers = aliasMatcher.group(1);
      final String name = aliasMatcher.group(2);
      final String target = aliasMatcher.group(3).trim();
      final String qualifiedAlias = qualifiedName(packageName, name);
      if (modifiers.contains("private") || modifiers.contains("internal") || result.containsKey(name)
          || !(packageName.equals(consumerPackage) || explicitlyImported.contains(qualifiedAlias))
          || result.containsKey(target) || !isDirectAliasTarget(target)) {
        continue;
      }
      result.put(name, target);
    }
  }

  private static boolean isVisibleGenericAlias(
      String modifiers,
      String packageName,
      String consumerPackage,
      Set<String> explicitlyImported,
      String qualifiedAlias,
      String target,
      boolean duplicate) {
    return !modifiers.contains("private") && !modifiers.contains("internal") && !duplicate
        && (packageName.equals(consumerPackage) || explicitlyImported.contains(qualifiedAlias))
        && !target.endsWith("?") && target.indexOf("->") < 0
        && target.indexOf('&') < 0 && target.indexOf('|') < 0;
  }

  private static boolean isDirectAliasTarget(String target) {
    return !target.endsWith("?") && target.indexOf("->") < 0 && target.indexOf('&') < 0
        && target.indexOf('|') < 0 && target.indexOf('(') < 0 && target.indexOf(')') < 0;
  }

  private static KotlinTypeDeclaration findDeclarationInFile(Path path, String qualifiedName) {
    final String source = FileManager.INSTANCE.getDocumentContents(path).toString();
    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
    final String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
    final List<KotlinJvmSyntaxParser.TopLevelTypeSyntax> syntaxTypes =
        KotlinJvmSyntaxParser.findTopLevelTypes(source);
    final List<KotlinJvmSyntaxParser.MemberSyntax> syntaxMembers =
        KotlinJvmSyntaxParser.findTopLevelMembers(source);
    final boolean hasTopLevelMember;

    if (syntaxTypes != null && syntaxMembers != null) {
      for (KotlinJvmSyntaxParser.TopLevelTypeSyntax type : syntaxTypes) {
        if (!type.privateType && qualifiedName.equals(qualifiedName(packageName, type.name))) {
          return new KotlinTypeDeclaration(path, type.nameOffset, type.nameLength);
        }
      }
      hasTopLevelMember = hasPublicTopLevelMember(syntaxMembers);
    } else {
      final KotlinTypeDeclaration fallback = findTypeDeclarationFallback(
          path, source, packageName, qualifiedName);
      if (fallback != null) {
        return fallback;
      }
      hasTopLevelMember = hasPublicTopLevelMemberFallback(source);
    }

    if (!hasTopLevelMember) {
      return null;
    }
    final Matcher jvmNameMatcher = FILE_JVM_NAME_PATTERN.matcher(source);
    final String facade = jvmNameMatcher.find()
        ? jvmNameMatcher.group(1)
        : path.getFileName().toString().substring(0, path.getFileName().toString().length() - 3) + "Kt";
    if (!qualifiedName.equals(qualifiedName(packageName, facade))) {
      return null;
    }
    final int declarationOffset = jvmNameMatcher.find(0) ? jvmNameMatcher.start(1) : 0;
    return new KotlinTypeDeclaration(path, declarationOffset, facade.length());
  }

  /**
   * Immutable module-revision snapshot of project alias declarations. It stores only compact
   * projection metadata, never source text or syntax trees. Consumer queries merely parse the
   * consumer package/imports and filter these maps; they do not walk source roots again.
   */
  static final class AliasDeclarationIndex {
    private final java.util.Map<String, java.util.List<DirectAliasDeclaration>> directByPackage;
    private final java.util.Map<String, java.util.List<GenericAliasDeclaration>> genericByPackage;

    private AliasDeclarationIndex(
        java.util.Map<String, java.util.List<DirectAliasDeclaration>> directByPackage,
        java.util.Map<String, java.util.List<GenericAliasDeclaration>> genericByPackage) {
      this.directByPackage = directByPackage;
      this.genericByPackage = genericByPackage;
    }

    static AliasDeclarationIndex build(Iterable<java.io.File> sourceRoots) {
      final java.util.Map<String, java.util.List<DirectAliasDeclaration>> direct =
          new java.util.LinkedHashMap<>();
      final java.util.Map<String, java.util.List<GenericAliasDeclaration>> generic =
          new java.util.LinkedHashMap<>();
      if (sourceRoots == null) return new AliasDeclarationIndex(direct, generic);
      for (java.io.File root : sourceRoots) {
        if (root == null || !root.isDirectory()) continue;
        try (Stream<Path> paths = Files.walk(root.toPath())) {
          paths.filter(DocumentUtils::isKotlinFile)
              .filter(path -> path.getFileName().toString().endsWith(".kt"))
              .forEach(path -> collect(path, direct, generic));
        } catch (IOException error) {
          LOG.debug("Unable to build Kotlin typealias declaration index for {}", root, error);
        }
      }
      return new AliasDeclarationIndex(freeze(direct), freeze(generic));
    }

    java.util.Map<String, String> visibleDirectAliases(Path consumerFile, String consumerSource) {
      final Visibility visibility = Visibility.from(consumerSource);
      final java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
      final Set<String> conflicts = new LinkedHashSet<>();
      for (java.util.List<DirectAliasDeclaration> declarations : directByPackage.values()) {
        for (DirectAliasDeclaration declaration : declarations) {
          if (normalizedPath(declaration.file).equals(normalizedPath(consumerFile))
              || !visibility.includes(declaration.packageName, declaration.qualifiedName)) continue;
          if (result.putIfAbsent(declaration.name, declaration.target) != null) {
            conflicts.add(declaration.name);
          }
        }
      }
      conflicts.forEach(result::remove);
      return result.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(result);
    }

    java.util.Map<String, GenericTypeAlias> visibleGenericAliases(Path consumerFile, String consumerSource) {
      final Visibility visibility = Visibility.from(consumerSource);
      final java.util.Map<String, GenericTypeAlias> result = new java.util.LinkedHashMap<>();
      final Set<String> conflicts = new LinkedHashSet<>();
      for (java.util.List<GenericAliasDeclaration> declarations : genericByPackage.values()) {
        for (GenericAliasDeclaration declaration : declarations) {
          if (normalizedPath(declaration.file).equals(normalizedPath(consumerFile))
              || !visibility.includes(declaration.packageName, declaration.qualifiedName)) continue;
          if (result.putIfAbsent(declaration.name, declaration.alias) != null) {
            conflicts.add(declaration.name);
          }
        }
      }
      conflicts.forEach(result::remove);
      return result.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(result);
    }

    private static void collect(
        Path path,
        java.util.Map<String, java.util.List<DirectAliasDeclaration>> direct,
        java.util.Map<String, java.util.List<GenericAliasDeclaration>> generic) {
      final String source = FileManager.INSTANCE.getDocumentContents(path).toString();
      final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
      final String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
      final Matcher directMatcher = TYPE_ALIAS_PATTERN.matcher(source);
      while (directMatcher.find()) {
        final String modifiers = directMatcher.group(1);
        final String target = directMatcher.group(3).trim();
        if (modifiers.contains("private") || modifiers.contains("internal") || !isDirectAliasTarget(target)) continue;
        final String name = directMatcher.group(2);
        direct.computeIfAbsent(packageName, ignored -> new java.util.ArrayList<>()).add(
            new DirectAliasDeclaration(path, packageName, name, qualifiedName(packageName, name), target));
      }
      final Matcher genericMatcher = GENERIC_TYPE_ALIAS_PATTERN.matcher(source);
      while (genericMatcher.find()) {
        final String modifiers = genericMatcher.group(1);
        final String target = genericMatcher.group(4).trim();
        final java.util.ArrayList<String> parameters = new java.util.ArrayList<>();
        boolean valid = !modifiers.contains("private") && !modifiers.contains("internal")
            && !target.endsWith("?") && target.indexOf("->") < 0 && target.indexOf('&') < 0
            && target.indexOf('|') < 0;
        for (String parameter : KotlinJvmTypeProjection.splitTopLevelArguments(genericMatcher.group(3))) {
          final String trimmed = parameter.trim();
          if (!TYPE_NAME_PATTERN.matcher(trimmed).matches()) valid = false;
          else parameters.add(trimmed);
        }
        final int open = target.indexOf('<');
        if (!valid || parameters.isEmpty() || open < 1 || !target.endsWith(">")) continue;
        final String raw = target.substring(0, open).trim();
        if (raw.indexOf('.') >= 0) continue;
        final java.util.ArrayList<String> arguments = new java.util.ArrayList<>();
        for (String argument : KotlinJvmTypeProjection.splitTopLevelArguments(target.substring(open + 1, target.length() - 1))) {
          final String trimmed = argument.trim();
          if (!TYPE_NAME_PATTERN.matcher(trimmed).matches()) valid = false;
          else arguments.add(trimmed);
        }
        if (!valid) continue;
        final String name = genericMatcher.group(2);
        generic.computeIfAbsent(packageName, ignored -> new java.util.ArrayList<>()).add(
            new GenericAliasDeclaration(path, packageName, name, qualifiedName(packageName, name),
                new GenericTypeAlias(parameters, raw, arguments)));
      }
    }

    private static <T> java.util.Map<String, java.util.List<T>> freeze(
        java.util.Map<String, java.util.List<T>> source) {
      final java.util.Map<String, java.util.List<T>> result = new java.util.LinkedHashMap<>();
      for (java.util.Map.Entry<String, java.util.List<T>> entry : source.entrySet()) {
        result.put(entry.getKey(), Collections.unmodifiableList(new java.util.ArrayList<>(entry.getValue())));
      }
      return Collections.unmodifiableMap(result);
    }
  }

  private static final class Visibility {
    final String consumerPackage;
    final Set<String> explicitlyImported;

    private Visibility(String consumerPackage, Set<String> explicitlyImported) {
      this.consumerPackage = consumerPackage;
      this.explicitlyImported = explicitlyImported;
    }

    static Visibility from(String source) {
      final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
      final String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
      final Set<String> imports = new LinkedHashSet<>();
      final Matcher importMatcher = IMPORT_PATTERN.matcher(source);
      while (importMatcher.find()) imports.add(importMatcher.group(1));
      return new Visibility(packageName, imports);
    }

    boolean includes(String declarationPackage, String qualifiedName) {
      return declarationPackage.equals(consumerPackage) || explicitlyImported.contains(qualifiedName);
    }
  }

  private static final class DirectAliasDeclaration {
    final Path file;
    final String packageName;
    final String name;
    final String qualifiedName;
    final String target;

    DirectAliasDeclaration(Path file, String packageName, String name, String qualifiedName, String target) {
      this.file = file;
      this.packageName = packageName;
      this.name = name;
      this.qualifiedName = qualifiedName;
      this.target = target;
    }
  }

  private static final class GenericAliasDeclaration {
    final Path file;
    final String packageName;
    final String name;
    final String qualifiedName;
    final GenericTypeAlias alias;

    GenericAliasDeclaration(Path file, String packageName, String name, String qualifiedName,
        GenericTypeAlias alias) {
      this.file = file;
      this.packageName = packageName;
      this.name = name;
      this.qualifiedName = qualifiedName;
      this.alias = alias;
    }
  }

  public static final class GenericTypeAlias {
    public final List<String> parameters;
    public final String targetRawType;
    public final List<String> targetArguments;

    GenericTypeAlias(List<String> parameters, String targetRawType, List<String> targetArguments) {
      this.parameters = Collections.unmodifiableList(new java.util.ArrayList<>(parameters));
      this.targetRawType = targetRawType;
      this.targetArguments = Collections.unmodifiableList(new java.util.ArrayList<>(targetArguments));
    }
  }

  public static final class KotlinTypeDeclaration {
    public final Path file;
    public final int offset;
    public final int length;

    KotlinTypeDeclaration(Path file, int offset, int length) {
      this.file = file;
      this.offset = offset;
      this.length = length;
    }
  }

  private static boolean isMultifileFacade(String source, Path path, String qualifiedName) {
    if (!FILE_JVM_MULTIFILE_PATTERN.matcher(source).find()) {
      return false;
    }
    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
    final String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
    return qualifiedName.equals(qualifiedName(packageName, facadeName(source, path)));
  }

  private static String facadeName(String source, Path path) {
    final Matcher jvmNameMatcher = FILE_JVM_NAME_PATTERN.matcher(source);
    return jvmNameMatcher.find()
        ? jvmNameMatcher.group(1)
        : path.getFileName().toString().substring(0, path.getFileName().toString().length() - 3) + "Kt";
  }

  private static int fileJvmNameOffset(String source) {
    final Matcher matcher = FILE_JVM_NAME_PATTERN.matcher(source);
    return matcher.find() ? matcher.start(1) : 0;
  }

  private static boolean hasPublicTopLevelMember(
      List<KotlinJvmSyntaxParser.MemberSyntax> members) {
    for (KotlinJvmSyntaxParser.MemberSyntax member : members) {
      if (!member.privateMember) {
        return true;
      }
    }
    return false;
  }

  private static boolean indexFileFallback(
      String source, String packageName, Set<String> result) {
    final String[] lines = source.split("\\R");
    int braceDepth = 0;
    boolean hasTopLevelMember = false;
    for (String line : lines) {
      if (braceDepth == 0) {
        final Matcher typeMatcher = TYPE_PATTERN.matcher(line);
        if (typeMatcher.find() && !containsPrivateModifier(typeMatcher.group(1))) {
          result.add(qualifiedName(packageName, typeMatcher.group(2)));
        }
        if (TOP_LEVEL_MEMBER_PATTERN.matcher(line).find() && !line.matches("^\\s*private\\s+.*")) {
          hasTopLevelMember = true;
        }
      }
      braceDepth = Math.max(0, braceDepth + braceDelta(line));
    }
    return hasTopLevelMember;
  }

  private static KotlinTypeDeclaration findTypeDeclarationFallback(
      Path path, String source, String packageName, String qualifiedName) {
    final String[] lines = source.split("\\R", -1);
    int braceDepth = 0;
    int offset = 0;
    for (String line : lines) {
      if (braceDepth == 0) {
        final Matcher typeMatcher = TYPE_PATTERN.matcher(line);
        if (typeMatcher.find() && !containsPrivateModifier(typeMatcher.group(1))) {
          final String name = typeMatcher.group(2);
          if (qualifiedName.equals(qualifiedName(packageName, name))) {
            return new KotlinTypeDeclaration(path, offset + typeMatcher.start(2), name.length());
          }
        }
      }
      braceDepth = Math.max(0, braceDepth + braceDelta(line));
      offset += line.length() + 1;
    }
    return null;
  }

  private static boolean hasPublicTopLevelMemberFallback(String source) {
    final String[] lines = source.split("\\R");
    int braceDepth = 0;
    for (String line : lines) {
      if (braceDepth == 0
          && TOP_LEVEL_MEMBER_PATTERN.matcher(line).find()
          && !line.matches("^\\s*private\\s+.*")) {
        return true;
      }
      braceDepth = Math.max(0, braceDepth + braceDelta(line));
    }
    return false;
  }

  private static boolean containsPrivateModifier(String modifiers) {
    return modifiers != null && Pattern.compile("(?:^|\\s)private(?:\\s|$)").matcher(modifiers).find();
  }

  private static String qualifiedName(String packageName, String simpleName) {
    return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
  }

  // This intentionally stays conservative. It is only used to avoid publishing clearly nested
  // declarations as package-level classes; ABI-accurate nested declarations belong to a later stub phase.
  private static int braceDelta(String line) {
    boolean quoted = false;
    boolean escaped = false;
    int delta = 0;
    for (int i = 0; i < line.length(); i++) {
      char current = line.charAt(i);
      if (quoted) {
        if (current == '"' && !escaped) {
          quoted = false;
        }
        escaped = current == '\\' && !escaped;
        continue;
      }
      if (current == '"') {
        quoted = true;
      } else if (current == '{') {
        delta++;
      } else if (current == '}') {
        delta--;
      }
    }
    return delta;
  }

  private static Path normalizedPath(Path file) {
    return file.toAbsolutePath().normalize();
  }

  static final class ConsumerAliasCache<T> {
    private final ConcurrentHashMap<Path, CachedAliasResult<T>> results = new ConcurrentHashMap<>();

    java.util.Map<String, T> get(Path file, String consumerSource) {
      if (file == null || consumerSource == null) return null;
      final CachedAliasResult<T> result = results.get(normalizedPath(file));
      return result != null && result.consumerSource.equals(consumerSource) ? result.aliases : null;
    }

    void put(Path file, String consumerSource, java.util.Map<String, T> aliases) {
      if (file == null || consumerSource == null || aliases == null) return;
      results.put(normalizedPath(file), new CachedAliasResult<>(consumerSource, aliases));
    }

    int size() {
      return results.size();
    }
  }

  private static final class CachedAliasResult<T> {
    final String consumerSource;
    final java.util.Map<String, T> aliases;

    CachedAliasResult(String consumerSource, java.util.Map<String, T> aliases) {
      this.consumerSource = consumerSource;
      this.aliases = aliases;
    }
  }

  private static final class CachedAliases {
    final long revision;
    final ConsumerAliasCache<String> directAliases = new ConsumerAliasCache<>();
    final ConsumerAliasCache<GenericTypeAlias> genericAliases = new ConsumerAliasCache<>();
    volatile AliasDeclarationIndex declarationIndex;

    CachedAliases(long revision) {
      this(revision, null);
    }

    CachedAliases(long revision, AliasDeclarationIndex declarationIndex) {
      this.revision = revision;
      this.declarationIndex = declarationIndex;
    }
  }

  private static final class CachedTypes {
    final long revision;
    final Set<String> types;

    CachedTypes(long revision, Set<String> types) {
      this.revision = revision;
      this.types = types;
    }
  }
}
