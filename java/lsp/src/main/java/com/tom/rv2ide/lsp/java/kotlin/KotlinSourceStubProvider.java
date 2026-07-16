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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdkx.tools.JavaFileObject;

/**
 * Produces conservative Kotlin-as-Java stubs for javac full compilations.
 *
 * <p>The initial implementation intentionally handles only explicitly imported top-level Kotlin
 * types and only emits type shells. It is a safe bridge for unresolved Kotlin types while the
 * richer symbol scanner and member ABI projection are introduced incrementally.
 */
public final class KotlinSourceStubProvider {

  private static final Pattern JAVA_IMPORT =
      Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)\\s*;");

  private KotlinSourceStubProvider() {}

  public static Collection<JavaFileObject> stubsFor(
      ModuleProject module, Collection<? extends JavaFileObject> javaSources) {
    if (module == null || javaSources == null || javaSources.isEmpty()) {
      return Collections.emptyList();
    }

    final Set<String> imports = new LinkedHashSet<>();
    for (JavaFileObject source : javaSources) {
      if (KotlinAbiStubJavaFileObject.isKotlinAbiStub(source)) {
        continue;
      }
      collectImports(source, imports);
    }

    final Set<String> sourceTypes = KotlinJvmTypeIndex.publicTopLevelTypes(module);
    final Set<String> classOutputTypes = KotlinClassOutputProvider.publicTopLevelTypes(module);
    final List<JavaFileObject> stubs = new ArrayList<>();
    for (String imported : imports) {
      // A real Kotlin class output has precedence. Do not create a duplicate javac type.
      if (!sourceTypes.contains(imported) || classOutputTypes.contains(imported)) {
        continue;
      }
      final KotlinJvmTypeIndex.KotlinTypeDeclaration declaration =
          KotlinJvmTypeIndex.findDeclaration(module, imported);
      if (declaration == null) {
        continue;
      }
      final String stub = generateTypeShell(imported, declaration.file);
      if (stub != null) {
        stubs.add(new KotlinAbiStubJavaFileObject(imported, stub, module.getSourceIndexVersion()));
      }
    }
    return stubs;
  }

  private static void collectImports(JavaFileObject source, Set<String> imports) {
    final CharSequence content;
    try {
      content = source.getCharContent(true);
    } catch (Exception ignored) {
      return;
    }
    final Matcher matcher = JAVA_IMPORT.matcher(content);
    while (matcher.find()) {
      imports.add(matcher.group(1));
    }
  }

  private static String generateTypeShell(String qualifiedName, Path kotlinFile) {
    final String source = FileManager.INSTANCE.getDocumentContents(kotlinFile).toString();
    final String packageName = qualifiedName.substring(0, Math.max(0, qualifiedName.lastIndexOf('.')));
    final String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    final String declaration = findDeclarationLine(source, simpleName);
    if (declaration == null) {
      return null;
    }

    final String kind;
    if (declaration.matches(".*\\binterface\\s+" + Pattern.quote(simpleName) + "\\b.*")) {
      kind = "interface";
    } else if (declaration.matches(".*\\benum\\s+class\\s+" + Pattern.quote(simpleName) + "\\b.*")) {
      kind = "enum";
    } else if (declaration.matches(".*\\bannotation\\s+class\\s+" + Pattern.quote(simpleName) + "\\b.*")) {
      kind = "@interface";
    } else {
      kind = "class";
    }

    final StringBuilder stub = new StringBuilder();
    if (!packageName.isEmpty()) {
      stub.append("package ").append(packageName).append(";\n\n");
    }
    if ("enum".equals(kind)) {
      stub.append("public enum ").append(simpleName).append(" { ; }\n");
    } else if ("@interface".equals(kind)) {
      stub.append("public @interface ").append(simpleName).append(" {}\n");
    } else {
      stub.append("public ").append(kind).append(' ').append(simpleName).append(" {}\n");
    }
    return stub.toString();
  }

  private static String findDeclarationLine(String source, String simpleName) {
    final Pattern declaration = Pattern.compile(
        "(?m)^\\s*(?:(?:public|protected|internal|private|open|abstract|sealed|data|enum|annotation|value)\\s+)*"
            + "(?:class|interface|object)\\s+" + Pattern.quote(simpleName) + "\\b.*$");
    final Matcher matcher = declaration.matcher(source);
    return matcher.find() ? matcher.group() : null;
  }
}