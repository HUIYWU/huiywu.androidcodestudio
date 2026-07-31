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
    if (module == null) {
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
    if (module == null || qualifiedName == null || qualifiedName.isEmpty()) {
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
    if (module == null || consumerFile == null) {
      return Collections.emptyMap();
    }
    final String consumerSource = FileManager.INSTANCE.getDocumentContents(consumerFile).toString();
    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(consumerSource);
    final String consumerPackage = packageMatcher.find() ? packageMatcher.group(1) : "";
    final Set<String> explicitlyImported = new LinkedHashSet<>();
    final Matcher importMatcher = IMPORT_PATTERN.matcher(consumerSource);
    while (importMatcher.find()) explicitlyImported.add(importMatcher.group(1));
    final java.util.Map<String, GenericTypeAlias> aliases = new java.util.LinkedHashMap<>();
    for (java.io.File root : module.getCompileSourceDirectories()) {
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
    if (module == null || consumerFile == null) {
      return Collections.emptyMap();
    }
    final String consumerSource = FileManager.INSTANCE.getDocumentContents(consumerFile).toString();
    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(consumerSource);
    final String consumerPackage = packageMatcher.find() ? packageMatcher.group(1) : "";
    final Set<String> explicitlyImported = new LinkedHashSet<>();
    final Matcher importMatcher = IMPORT_PATTERN.matcher(consumerSource);
    while (importMatcher.find()) {
      explicitlyImported.add(importMatcher.group(1));
    }
    final java.util.Map<String, String> aliases = new java.util.LinkedHashMap<>();
    for (java.io.File root : module.getCompileSourceDirectories()) {
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
    if (module == null || qualifiedName == null || qualifiedName.isEmpty()) {
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

  /** Removes cached Kotlin source symbols for a module, e.g. after a source-root change. */
  public static void invalidate(ModuleProject module) {
    if (module != null) {
      CACHE.remove(module);
    }
  }

  public static void clear() {
    CACHE.clear();
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
      if (!isVisibleGenericAlias(modifiers, packageName, consumerPackage,
          explicitlyImported, qualifiedAlias, target, result.containsKey(name))) continue;
      final java.util.ArrayList<String> parameters = new java.util.ArrayList<>();
      boolean valid = true;
      for (String parameter : splitTypeArguments(matcher.group(3))) {
        final String trimmed = parameter.trim();
        if (!TYPE_NAME_PATTERN.matcher(trimmed).matches()) { valid = false; break; }
        parameters.add(trimmed);
      }
      final int open = target.indexOf('<');
      if (!valid || parameters.isEmpty() || open < 1 || !target.endsWith(">")) continue;
      final String raw = target.substring(0, open).trim();
      if (raw.indexOf('.') >= 0) continue;
      final java.util.ArrayList<String> arguments = new java.util.ArrayList<>();
      for (String argument : splitTypeArguments(target.substring(open + 1, target.length() - 1))) {
        final String trimmed = argument.trim();
        if (!TYPE_NAME_PATTERN.matcher(trimmed).matches()) { valid = false; break; }
        arguments.add(trimmed);
      }
      if (valid) result.put(name, new GenericTypeAlias(parameters, raw, arguments));
    }
  }

  private static java.util.List<String> splitTypeArguments(String text) {
    final java.util.ArrayList<String> result = new java.util.ArrayList<>();
    int start = 0, nesting = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '<') nesting++; else if (c == '>') nesting--;
      else if (c == ',' && nesting == 0) { result.add(text.substring(start, i)); start = i + 1; }
    }
    result.add(text.substring(start));
    return result;
  }

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

  private static final class CachedTypes {
    final long revision;
    final Set<String> types;

    CachedTypes(long revision, Set<String> types) {
      this.revision = revision;
      this.types = types;
    }
  }
}
