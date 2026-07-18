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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative source-level Kotlin-to-Java ABI projection used while compiler-provided Kotlin JVM
 * symbols are unavailable.
 *
 * <p>Tree-sitter supplies declaration and body boundaries while JVM projection remains deliberately
 * conservative. Only common declarations whose JVM form is sufficiently unambiguous are emitted;
 * unsupported signatures use {@link Object} to preserve Java attribution without claiming false
 * Kotlin semantic precision.
 */
final class KotlinJvmAbiStubGenerator {

  private static final Pattern PACKAGE_PATTERN =
      Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)");
  private static final Pattern FILE_JVM_NAME_PATTERN =
      Pattern.compile("(?m)^\\s*@file:JvmName\\s*\\(\\s*\\\"([A-Za-z_$][\\w$]*)\\\"\\s*\\)");
  private static final Pattern KOTLIN_IMPORT_PATTERN =
      Pattern.compile(
          "(?m)^\\s*import\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)(\\.\\*)?"
              + "(?:\\s+as\\s+([A-Za-z_$][\\w$]*))?\\s*$");
  private static final Pattern JAVA_TYPE_NAME_PATTERN =
      Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*");
  private static final ThreadLocal<TypeResolutionContext> TYPE_CONTEXT = new ThreadLocal<>();
  private static final Pattern TYPE_PATTERN =
      Pattern.compile(
          "(?m)^\\s*((?:(?:public|protected|internal|private|open|abstract|sealed|data|value)\\s+)*)"
              + "((?:enum\\s+class)|annotation\\s+class|class|interface|object)"
              + "\\s+([A-Za-z_][\\w]*)(?:\\s*<[^>{}()]*>)?");
  private static final Pattern FUNCTION_PATTERN =
      Pattern.compile(
          "^\\s*((?:(?:public|protected|internal|private|open|abstract|final|override|suspend|"
              + "operator|infix|inline|tailrec|external)\\s+)*)fun\\s+(?:<[^>]+>\\s*)?"
              + "([A-Za-z_][\\w]*)\\s*\\(([^)]*)\\)\\s*(?::\\s*([^=\\{]+))?.*$");
  private static final Pattern EXTENSION_FUNCTION_PATTERN =
      Pattern.compile(
          "^\\s*((?:(?:public|protected|internal|private|open|abstract|final|override|suspend|"
              + "operator|infix|inline|tailrec|external)\\s+)*)fun\\s+(?:<[^>]+>\\s*)?"
              + "([A-Za-z_][\\w]*(?:<[^>]+>)?\\??)\\.([A-Za-z_][\\w]*)\\s*\\(([^)]*)\\)"
              + "\\s*(?::\\s*([^=\\{]+))?.*$");
private static final Pattern PROPERTY_PATTERN =
      Pattern.compile(
          "^\\s*((?:(?:public|protected|internal|private|open|override|const|lateinit)\\s+)*)"
              + "(val|var)\\s+([A-Za-z_][\\w]*)\\s*(?::\\s*([^=\\{]+))?.*$");
  private static final Pattern JVM_STATIC_FUNCTION_PATTERN =
      Pattern.compile(
          "(?s)@JvmStatic\\s*(?:\\([^)]*\\))?\\s*"
              + "((?:(?:public|protected|internal|private|open|abstract|final|override|suspend|"
              + "operator|infix|inline|tailrec|external)\\s+)*)fun\\s+(?:<[^>]+>\\s*)?"
              + "([A-Za-z_][\\w]*)\\s*\\(([^)]*)\\)\\s*(?::\\s*([^=\\{]+))?");
  private static final Pattern JVM_STATIC_PROPERTY_PATTERN =
      Pattern.compile(
          "(?s)@JvmStatic\\s*(?:\\([^)]*\\))?\\s*"
              + "((?:(?:public|protected|internal|private|open|override|const|lateinit)\\s+)*)"
              + "(val|var)\\s+([A-Za-z_][\\w]*)\\s*(?::\\s*([^=\\{]+))?");
  private static final Pattern JVM_FIELD_PROPERTY_PATTERN =
      Pattern.compile(
          "(?s)@JvmField\\s*(?:\\([^)]*\\))?\\s*"
              + "((?:(?:public|protected|internal|private|open|override|const|lateinit)\\s+)*)"
              + "(val|var)\\s+([A-Za-z_][\\w]*)\\s*(?::\\s*([^=\\{]+))?");

  private KotlinJvmAbiStubGenerator() {}

  static String generate(String qualifiedName, String kotlinFileName, String source) {
    return generate(qualifiedName, kotlinFileName, source, java.util.Collections.emptySet());
  }

  static String generate(
      String qualifiedName, String kotlinFileName, String source, Set<String> knownTypes) {
    final int separator = qualifiedName.lastIndexOf('.');
    final String packageName = separator < 0 ? "" : qualifiedName.substring(0, separator);
    final String simpleName = separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
    final String sourcePackage = packageMatcher.find() ? packageMatcher.group(1) : "";
    if (!packageName.equals(sourcePackage)) {
      return null;
    }

    final TypeResolutionContext previous = TYPE_CONTEXT.get();
    TYPE_CONTEXT.set(TypeResolutionContext.create(sourcePackage, source, knownTypes));
    try {
      final KotlinJvmSyntaxParser.TypeSyntax syntax =
          KotlinJvmSyntaxParser.findTopLevelType(source, simpleName);
      if (syntax != null) {
        return syntax.privateType ? null : generateType(packageName, simpleName, syntax);
      }

      // Retain the previous scanner as a compatibility fallback when the native grammar is
      // unavailable or an incomplete edit produces no usable top-level declaration.
      final Matcher typeMatcher = TYPE_PATTERN.matcher(source);
      while (typeMatcher.find()) {
        if (simpleName.equals(typeMatcher.group(3)) && !isPrivate(typeMatcher.group(1))) {
          return generateTypeFallback(packageName, simpleName, typeMatcher, source);
        }
      }
      return isFacadeName(simpleName, kotlinFileName, source)
          ? generateFacade(packageName, simpleName, source)
          : null;
    } finally {
      if (previous == null) {
        TYPE_CONTEXT.remove();
      } else {
        TYPE_CONTEXT.set(previous);
      }
    }
  }

  private static String generateType(
      String packageName, String simpleName, KotlinJvmSyntaxParser.TypeSyntax syntax) {
    final boolean isObject = syntax.objectType();
    final boolean isInterface = syntax.interfaceType;
    final StringBuilder out = header(packageName);
    if (syntax.enumType) {
      out.append("public enum ").append(simpleName).append(" { ; }\n");
      return out.toString();
    }
    if (syntax.annotationType) {
      out.append("public @interface ").append(simpleName).append(" {}\n");
      return out.toString();
    }
    out.append("public ").append(isInterface ? "interface " : "class ")
        .append(simpleName).append(" {\n");
    if (isObject) {
      out.append("  public static final ").append(simpleName).append(" INSTANCE = null;\n");
    } else if (!isInterface) {
      out.append("  public ").append(simpleName)
          .append(javaConstructorParameterList(syntax.constructorParameters))
          .append(" {}\n");
      appendSyntaxConstructorProperties(out, syntax.constructorParameters);
    }
    appendSyntaxMembers(out, syntax.members, isInterface, false);
    if (syntax.companionBody != null) {
      appendCompanionSyntax(out, syntax.companionMembers);
    }
    out.append("}\n");
    return out.toString();
  }

  private static String generateTypeFallback(
      String packageName, String simpleName, Matcher declaration, String source) {
    final String keyword = declaration.group(2);
    final boolean isObject = "object".equals(keyword);
    final boolean isInterface = "interface".equals(keyword);
    final boolean isEnum = keyword.startsWith("enum");
    final boolean isAnnotation = keyword.startsWith("annotation");
    final StringBuilder out = header(packageName);
    if (isEnum) {
      out.append("public enum ").append(simpleName).append(" { ; }\n");
      return out.toString();
    }
    if (isAnnotation) {
      out.append("public @interface ").append(simpleName).append(" {}\n");
      return out.toString();
    }
    out.append("public ");
    if (isInterface) {
      out.append("interface ");
    } else {
      out.append("class ");
    }
    out.append(simpleName).append(" {\n");
    final String constructor =
        isObject || isInterface ? null : primaryConstructorText(source, declaration.end(3));
    if (isObject) {
      out.append("  public static final ").append(simpleName).append(" INSTANCE = null;\n");
    } else if (!isInterface) {
      out.append("  public ").append(simpleName).append(parameterList(constructor)).append(" {}\n");
      appendConstructorProperties(out, constructor);
    }

    int bodySearchStart = declaration.end();
    if (constructor != null) {
      final int constructorStart = source.indexOf(constructor, declaration.end(3));
      if (constructorStart >= 0) {
        bodySearchStart = constructorStart + constructor.length();
      }
    }
    final int bodyStart = source.indexOf('{', bodySearchStart);
    if (bodyStart >= 0) {
      final int bodyEnd = matchingBrace(source, bodyStart);
      if (bodyEnd > bodyStart) {
        final String body = source.substring(bodyStart + 1, bodyEnd);
        appendMembers(out, body, isInterface, false);
        appendCompanionMembers(out, body);
      }
    }
    out.append("}\n");
    return out.toString();
  }

  private static String generateFacade(String packageName, String simpleName, String source) {
    final StringBuilder out = header(packageName);
    out.append("public final class ").append(simpleName).append(" {\n");
    out.append("  private ").append(simpleName).append("() {}\n");
    final List<KotlinJvmSyntaxParser.MemberSyntax> members =
        KotlinJvmSyntaxParser.findTopLevelMembers(source);
    if (members != null) {
      appendSyntaxMembers(out, members, false, true);
    } else {
      appendMembers(out, source, false, true);
    }
    out.append("}\n");
    return out.toString();
  }

  private static void appendCompanionMembers(StringBuilder out, String body) {
    // Anchor the declaration at the beginning of a source line so examples such as
    // `// companion object {` do not shadow the real companion declared later in the class.
    final Matcher companion =
        Pattern.compile("(?m)^\\s*companion\\s+object(?:\\s+[A-Za-z_][\\w]*)?\\s*\\{")
            .matcher(body);
    if (!companion.find()) {
      return;
    }
    final int bodyStart = companion.end() - 1;
    final int bodyEnd = matchingBrace(body, bodyStart);
    if (bodyEnd <= bodyStart) {
      return;
    }
    final String companionBody = body.substring(bodyStart + 1, bodyEnd);
    appendCompanionBody(out, companionBody);
  }

  private static void appendCompanionBody(StringBuilder out, String companionBody) {
    // Kotlin exposes the companion itself as Foo.Companion. Keep that normal JVM surface in
    // addition to the direct host-class methods created only for @JvmStatic declarations.
    out.append("  public static final class Companion {\n");
    appendMembers(
        out, companionBody.replace("@JvmStatic", "").replace("@JvmField", ""), false, false);
    out.append("  }\n");
    out.append("  public static final Companion Companion = null;\n");
    // Do not depend on annotations and declarations sharing a particular line layout. Kotlin permits
    // @JvmStatic, its use-site arguments, whitespace and the declaration to span separate lines.
    final Matcher staticFunction = JVM_STATIC_FUNCTION_PATTERN.matcher(companionBody);
    while (staticFunction.find()) {
      if (!isPrivate(staticFunction.group(1))) {
        appendStaticFunction(out, staticFunction);
      }
    }
    final Matcher staticProperty = JVM_STATIC_PROPERTY_PATTERN.matcher(companionBody);
    while (staticProperty.find()) {
      if (!isPrivate(staticProperty.group(1))) {
        appendStaticProperty(out, staticProperty);
      }
    }
    appendJvmFields(out, companionBody);
  }

  private static void appendSyntaxMembers(
      StringBuilder out,
      List<KotlinJvmSyntaxParser.MemberSyntax> members,
      boolean interfaceType,
      boolean topLevel) {
    for (KotlinJvmSyntaxParser.MemberSyntax member : members) {
      if (member.privateMember || member.declarationText.isEmpty()) {
        continue;
      }
      if (member.function()) {
        appendSyntaxFunction(out, member, interfaceType, topLevel);
        if (member.jvmOverloads) {
          appendSyntaxFunctionOverloads(out, member, interfaceType, topLevel);
        }
      } else {
        appendSyntaxProperty(out, member, interfaceType, topLevel);
      }
    }
  }

  private static void appendCompanionSyntax(
      StringBuilder out, List<KotlinJvmSyntaxParser.MemberSyntax> members) {
    out.append("  public static final class Companion {\n");
    appendSyntaxMembers(out, members, false, false);
    out.append("  }\n");
    out.append("  public static final Companion Companion = null;\n");
    for (KotlinJvmSyntaxParser.MemberSyntax member : members) {
      if (member.privateMember || member.declarationText.isEmpty()) {
        continue;
      }
      if (member.function() && member.jvmStatic) {
        appendSyntaxFunction(out, member, false, true);
        if (member.jvmOverloads) {
          appendSyntaxFunctionOverloads(out, member, false, true);
        }
      } else if (!member.function()) {
        if (member.jvmField) {
          appendSyntaxField(out, member);
        } else if (member.jvmStatic) {
          appendSyntaxProperty(out, member, false, true);
        }
      }
    }
  }

  private static void appendSyntaxFunction(
      StringBuilder out,
      KotlinJvmSyntaxParser.MemberSyntax function,
      boolean interfaceType,
      boolean topLevel) {
    if (function.name == null || function.name.isEmpty()) {
      return;
    }
    out.append("  public ");
    if (topLevel) {
      out.append("static ");
    }
    final List<String> parameters = new ArrayList<>();
    if (function.receiverType != null) {
      parameters.add(javaType(function.receiverType) + " receiver");
    }
    for (int index = 0; index < function.parameterList.size(); index++) {
      final KotlinJvmSyntaxParser.ParameterSyntax parameter = function.parameterList.get(index);
      parameters.add(javaType(parameter.type) + " " + safeName(parameter.name, index));
    }
    out.append(javaType(function.declaredType)).append(' ').append(function.name)
        .append('(').append(String.join(", ", parameters)).append(')')
        .append(interfaceType && !topLevel ? ";\n" : methodBody(function.declaredType));
  }

  private static void appendSyntaxFunctionOverloads(
      StringBuilder out,
      KotlinJvmSyntaxParser.MemberSyntax function,
      boolean interfaceType,
      boolean topLevel) {
    if (function.name == null || function.receiverType != null) {
      return;
    }
    final List<KotlinJvmSyntaxParser.ParameterSyntax> parameters = function.parameterList;
    int firstOmittable = parameters.size();
    for (int index = parameters.size() - 1;
        index >= 0 && parameters.get(index).defaultValue; index--) {
      firstOmittable = index;
    }
    if (firstOmittable == parameters.size()) {
      return;
    }
    for (int count = parameters.size() - 1; count >= firstOmittable; count--) {
      out.append("  public ");
      if (topLevel) {
        out.append("static ");
      }
      out.append(javaType(function.declaredType)).append(' ').append(function.name)
          .append(javaSyntaxParameterList(parameters.subList(0, count)))
          .append(interfaceType && !topLevel ? ";\n" : methodBody(function.declaredType));
    }
  }

  private static void appendSyntaxProperty(
      StringBuilder out,
      KotlinJvmSyntaxParser.MemberSyntax property,
      boolean interfaceType,
      boolean topLevel) {
    if (property.name == null || property.receiverType != null) {
      return;
    }
    final String type = javaType(property.declaredType);
    final String accessor = Character.toUpperCase(property.name.charAt(0)) + property.name.substring(1);
    out.append("  public ");
    if (topLevel) {
      out.append("static ");
    }
    out.append(type).append(" get").append(accessor).append("()")
        .append(interfaceType && !topLevel
            ? ";\n"
            : " { return " + defaultValue(property.declaredType) + "; }\n");
    if (property.mutableProperty) {
      out.append("  public ");
      if (topLevel) {
        out.append("static ");
      }
      out.append("void set").append(accessor).append('(').append(type).append(" value)")
          .append(interfaceType && !topLevel ? ";\n" : " {}\n");
    }
  }

  private static void appendSyntaxField(
      StringBuilder out, KotlinJvmSyntaxParser.MemberSyntax property) {
    if (property.name != null && property.receiverType == null) {
      out.append("  public static ").append(javaType(property.declaredType)).append(' ')
          .append(property.name).append(";\n");
    }
  }

  private static void appendExtensionFunction(
      StringBuilder out, Matcher extension, boolean interfaceType, boolean topLevel) {
    out.append("  public ");
    if (topLevel) {
      out.append("static ");
    }
    final List<String> parameters = new ArrayList<>();
    parameters.add(javaType(extension.group(2)) + " receiver");
    final String ordinaryParameters = javaParameterList(splitParameters(extension.group(4)));
    if (ordinaryParameters.length() > 2) {
      parameters.add(ordinaryParameters.substring(1, ordinaryParameters.length() - 1));
    }
    out.append(javaType(extension.group(5))).append(' ').append(extension.group(3))
        .append("(").append(String.join(", ", parameters)).append(")");
    out.append(interfaceType && !topLevel ? ";\n" : methodBody(extension.group(5)));
  }

  private static void appendFunction(
      StringBuilder out, Matcher function, boolean interfaceType, boolean topLevel) {
    out.append("  public ");
    if (topLevel) {
      out.append("static ");
    }
    out.append(javaType(function.group(4))).append(' ').append(function.group(2))
        .append(parameterList("(" + function.group(3) + ")"));
    out.append(interfaceType && !topLevel ? ";\n" : methodBody(function.group(4)));
  }

  private static void appendFunctionOverloads(
      StringBuilder out, Matcher function, boolean interfaceType, boolean topLevel) {
    final List<String> parameters = splitParameters(function.group(3));
    int firstOmittable = parameters.size();
    for (int index = parameters.size() - 1;
        index >= 0 && hasDefaultValue(parameters.get(index));
        index--) {
      firstOmittable = index;
    }
    // Kotlin only creates overloads by dropping a contiguous trailing suffix of default-valued
    // parameters. A default value before a required parameter does not create a Java overload.
    if (firstOmittable == parameters.size()) {
      return;
    }
    for (int count = parameters.size() - 1; count >= firstOmittable; count--) {
      out.append("  public ");
      if (topLevel) {
        out.append("static ");
      }
      out.append(javaType(function.group(4))).append(' ').append(function.group(2))
          .append(javaParameterList(parameters.subList(0, count)));
      out.append(interfaceType && !topLevel ? ";\n" : methodBody(function.group(4)));
    }
  }

  private static void appendStaticFunction(StringBuilder out, Matcher function) {
    out.append("  public static ").append(javaType(function.group(4))).append(' ')
        .append(function.group(2)).append(parameterList("(" + function.group(3) + ")"))
        .append(methodBody(function.group(4)));
  }

  private static void appendJvmFields(StringBuilder out, String companionBody) {
    final Matcher field = JVM_FIELD_PROPERTY_PATTERN.matcher(companionBody);
    while (field.find()) {
      if (!isPrivate(field.group(1))) {
        appendStaticField(out, field);
      }
    }
  }

  private static void appendStaticField(StringBuilder out, Matcher property) {
    out.append("  public static ").append(javaType(property.group(4))).append(' ')
        .append(property.group(3)).append(";\n");
  }

  private static void appendProperty(
      StringBuilder out, Matcher property, boolean interfaceType, boolean topLevel) {
    final String type = javaType(property.group(4));
    final String name = property.group(3);
    final String accessor = Character.toUpperCase(name.charAt(0)) + name.substring(1);
    out.append("  public ");
    if (topLevel) {
      out.append("static ");
    }
    out.append(type).append(" get").append(accessor).append("()")
        .append(interfaceType && !topLevel
            ? ";\n"
            : " { return " + defaultValue(property.group(4)) + "; }\n");
    if ("var".equals(property.group(2))) {
      out.append("  public ");
      if (topLevel) {
        out.append("static ");
      }
      out.append("void set").append(accessor).append('(').append(type).append(" value)")
          .append(interfaceType && !topLevel ? ";\n" : " {}\n");
    }
  }

  private static void appendStaticProperty(StringBuilder out, Matcher property) {
    final String type = javaType(property.group(4));
    final String name = property.group(3);
    final String accessor = Character.toUpperCase(name.charAt(0)) + name.substring(1);
    out.append("  public static ").append(type).append(" get").append(accessor).append("()")
        .append(" { return ").append(defaultValue(property.group(4))).append("; }\n");
    if ("var".equals(property.group(2))) {
      out.append("  public static void set").append(accessor).append('(').append(type)
          .append(" value) {}\n");
    }
  }

  private static String javaConstructorParameterList(
      List<KotlinJvmSyntaxParser.ConstructorParameterSyntax> kotlinParameters) {
    final List<String> parameters = new ArrayList<>();
    for (int index = 0; index < kotlinParameters.size(); index++) {
      final KotlinJvmSyntaxParser.ConstructorParameterSyntax parameter = kotlinParameters.get(index);
      parameters.add(javaType(parameter.type) + " " + safeName(parameter.name, index));
    }
    return "(" + String.join(", ", parameters) + ")";
  }

  private static void appendSyntaxConstructorProperties(
      StringBuilder out,
      List<KotlinJvmSyntaxParser.ConstructorParameterSyntax> kotlinParameters) {
    for (int index = 0; index < kotlinParameters.size(); index++) {
      final KotlinJvmSyntaxParser.ConstructorParameterSyntax parameter = kotlinParameters.get(index);
      if (!parameter.property || parameter.name == null || parameter.name.isEmpty()) {
        continue;
      }
      final String name = safeName(parameter.name, index);
      final String kotlinType = parameter.type;
      final String type = javaType(kotlinType);
      final String accessor = Character.toUpperCase(name.charAt(0)) + name.substring(1);
      out.append("  public ").append(type).append(" get").append(accessor)
          .append("() { return ").append(defaultValue(kotlinType)).append("; }\n");
      if (parameter.mutableProperty) {
        out.append("  public void set").append(accessor).append('(').append(type)
            .append(" value) {}\n");
      }
    }
  }

  private static void appendConstructorProperties(StringBuilder out, String kotlinParameters) {
    if (kotlinParameters == null || kotlinParameters.length() < 2) {
      return;
    }
    for (String raw :
        splitParameters(kotlinParameters.substring(1, kotlinParameters.length() - 1))) {
      final String part = raw.trim();
      if (!part.startsWith("val ") && !part.startsWith("var ")) {
        continue;
      }
      final String declaration = part.substring(4).trim();
      final int colon = declaration.indexOf(':');
      if (colon < 1) {
        continue;
      }
      final String name = declaration.substring(0, colon).trim();
      if (!name.matches("[A-Za-z_$][\\w$]*")) {
        continue;
      }
      final String kotlinType = declaration.substring(colon + 1);
      final String type = javaType(kotlinType);
      final String accessor = Character.toUpperCase(name.charAt(0)) + name.substring(1);
      out.append("  public ").append(type).append(" get").append(accessor).append("() { return ")
          .append(defaultValue(kotlinType)).append("; }\n");
      if (part.startsWith("var ")) {
        out.append("  public void set").append(accessor).append('(').append(type)
            .append(" value) {}\n");
      }
    }
  }

  private static void appendMembers(
      StringBuilder out, String source, boolean interfaceType, boolean topLevel) {
    int depth = 0;
    boolean jvmOverloads = false;
    for (String line : source.split("\\R")) {
      if (depth == 0) {
        final boolean hasJvmOverloads = line.contains("@JvmOverloads");
        final String declarationLine = line.replace("@JvmOverloads", "").trim();
        if (hasJvmOverloads) {
          jvmOverloads = true;
        }
        final Matcher extensionFunction = EXTENSION_FUNCTION_PATTERN.matcher(declarationLine);
        if (extensionFunction.matches() && !isPrivate(extensionFunction.group(1))) {
          appendExtensionFunction(out, extensionFunction, interfaceType, topLevel);
          jvmOverloads = false;
        } else {
          final Matcher function = FUNCTION_PATTERN.matcher(declarationLine);
          if (function.matches() && !isPrivate(function.group(1))) {
            appendFunction(out, function, interfaceType, topLevel);
            if (jvmOverloads) {
              appendFunctionOverloads(out, function, interfaceType, topLevel);
            }
            jvmOverloads = false;
            depth += braceDelta(line);
            if (depth < 0) {
              depth = 0;
            }
            continue;
          }
          if (jvmOverloads
              && !declarationLine.isEmpty()
              && !declarationLine.startsWith("@")
              && !PROPERTY_PATTERN.matcher(declarationLine).matches()) {
            jvmOverloads = false;
          }
          final Matcher property = PROPERTY_PATTERN.matcher(declarationLine);
          if (property.matches() && !isPrivate(property.group(1))) {
            jvmOverloads = false;
            final String type = javaType(property.group(4));
            final String name = property.group(3);
            final String accessor = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            out.append("  public ");
            if (topLevel) {
              out.append("static ");
            }
            out.append(type).append(" get").append(accessor).append("()")
                .append(interfaceType && !topLevel ? ";\n" : " { return " + defaultValue(property.group(4)) + "; }\n");
            if ("var".equals(property.group(2))) {
              out.append("  public ");
              if (topLevel) {
                out.append("static ");
              }
              out.append("void set").append(accessor).append('(').append(type).append(" value)")
                  .append(interfaceType && !topLevel ? ";\n" : " {}\n");
            }
          }
        }
      }
      depth += braceDelta(line);
      if (depth < 0) {
        depth = 0;
      }
    }
  }

  private static String primaryConstructorText(String source, int classNameEnd) {
    int angleDepth = 0;
    for (int index = classNameEnd; index < source.length(); index++) {
      final char current = source.charAt(index);
      if (current == '<') {
        angleDepth++;
      } else if (current == '>') {
        angleDepth = Math.max(0, angleDepth - 1);
      } else if (angleDepth == 0 && (current == ':' || current == '{')) {
        return null;
      } else if (angleDepth == 0 && current == '(') {
        final int end = matchingDelimiter(source, index, '(', ')');
        return end < 0 ? null : source.substring(index, end + 1);
      }
    }
    return null;
  }

  private static int matchingDelimiter(String text, int open, char opening, char closing) {
    int depth = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int index = open; index < text.length(); index++) {
      final char current = text.charAt(index);
      if (quoted) {
        if (current == '"' && !escaped) {
          quoted = false;
        }
        escaped = current == '\\' && !escaped;
        if (current != '\\') {
          escaped = false;
        }
        continue;
      }
      if (current == '"') {
        quoted = true;
      } else if (current == opening) {
        depth++;
      } else if (current == closing && --depth == 0) {
        return index;
      }
    }
    return -1;
  }

  private static String parameterList(String kotlinParameters) {
    if (kotlinParameters == null || kotlinParameters.length() < 2) {
      return "()";
    }
    return javaParameterList(
        splitParameters(kotlinParameters.substring(1, kotlinParameters.length() - 1)));
  }

  private static String javaSyntaxParameterList(
      List<KotlinJvmSyntaxParser.ParameterSyntax> kotlinParameters) {
    final List<String> parameters = new ArrayList<>();
    for (int index = 0; index < kotlinParameters.size(); index++) {
      final KotlinJvmSyntaxParser.ParameterSyntax parameter = kotlinParameters.get(index);
      parameters.add(javaType(parameter.type) + " " + safeName(parameter.name, index));
    }
    return "(" + String.join(", ", parameters) + ")";
  }

  private static String javaParameterList(List<String> kotlinParameters) {
    final List<String> parameters = new ArrayList<>();
    for (int index = 0; index < kotlinParameters.size(); index++) {
      String part = kotlinParameters.get(index).trim()
          .replaceAll("^(?:val|var|crossinline|noinline)\\s+", "");
      final int colon = part.indexOf(':');
      final String name = colon < 0 ? "arg" + index : part.substring(0, colon).trim();
      final String type = colon < 0 ? "Object" : javaType(part.substring(colon + 1));
      parameters.add(type + " " + safeName(name, index));
    }
    return "(" + String.join(", ", parameters) + ")";
  }

  private static List<String> splitParameters(String parameterText) {
    final List<String> parameters = new ArrayList<>();
    if (parameterText == null || parameterText.trim().isEmpty()) {
      return parameters;
    }
    int start = 0;
    int nesting = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int index = 0; index < parameterText.length(); index++) {
      final char current = parameterText.charAt(index);
      if (quoted) {
        if (current == '"' && !escaped) {
          quoted = false;
        }
        escaped = current == '\\' && !escaped;
        continue;
      }
      if (current == '"') {
        quoted = true;
      } else if (current == '(' || current == '<' || current == '[' || current == '{') {
        nesting++;
      } else if (current == ')' || current == '>' || current == ']' || current == '}') {
        nesting = Math.max(0, nesting - 1);
      } else if (current == ',' && nesting == 0) {
        parameters.add(parameterText.substring(start, index));
        start = index + 1;
      }
    }
    parameters.add(parameterText.substring(start));
    return parameters;
  }

  private static boolean hasDefaultValue(String parameter) {
    int nesting = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int index = 0; index < parameter.length(); index++) {
      final char current = parameter.charAt(index);
      if (quoted) {
        if (current == '"' && !escaped) {
          quoted = false;
        }
        escaped = current == '\\' && !escaped;
      } else if (current == '"') {
        quoted = true;
      } else if (current == '(' || current == '<' || current == '[' || current == '{') {
        nesting++;
      } else if (current == ')' || current == '>' || current == ']' || current == '}') {
        nesting = Math.max(0, nesting - 1);
      } else if (current == '=' && nesting == 0) {
        return true;
      }
    }
    return false;
  }

  private static String javaType(String kotlinType) {
    if (kotlinType == null || kotlinType.trim().isEmpty()) {
      return "Object";
    }
    String type = stripDefaultValue(kotlinType.trim());
    final boolean nullable = type.endsWith("?");
    if (nullable) {
      type = type.substring(0, type.length() - 1).trim();
    }

    final String primitive = javaPrimitiveType(type);
    if (primitive != null) {
      return nullable ? boxedType(primitive) : primitive;
    }
    if ("String".equals(type) || "kotlin.String".equals(type)) return "String";
    if ("Unit".equals(type) || "kotlin.Unit".equals(type)) return nullable ? "Object" : "void";
    if ("Any".equals(type) || "kotlin.Any".equals(type)
        || "Nothing".equals(type) || "kotlin.Nothing".equals(type)) {
      return "Object";
    }

    final String primitiveArray = javaPrimitiveArrayType(type);
    if (primitiveArray != null) {
      return primitiveArray;
    }

    final TypeApplication application = parseTypeApplication(type);
    if (application == null) {
      return javaUserType(type);
    }
    final String rawJavaType = javaCollectionType(application.rawType);
    if ("Array".equals(application.rawType) || "kotlin.Array".equals(application.rawType)) {
      return application.arguments.size() == 1
          ? javaReferenceArrayType(application.arguments.get(0))
          : "Object[]";
    }
    final String resolvedRawType =
        rawJavaType == null ? javaUserType(application.rawType) : rawJavaType;
    if ("Object".equals(resolvedRawType)) {
      return "Object";
    }
    final List<String> arguments = new ArrayList<>();
    for (String argument : application.arguments) {
      arguments.add(javaTypeArgument(argument));
    }
    return arguments.isEmpty()
        ? resolvedRawType
        : resolvedRawType + "<" + String.join(", ", arguments) + ">";
  }

  private static String stripDefaultValue(String type) {
    int nesting = 0;
    for (int index = 0; index < type.length(); index++) {
      final char current = type.charAt(index);
      if (current == '<' || current == '(' || current == '[') {
        nesting++;
      } else if (current == '>' || current == ')' || current == ']') {
        nesting = Math.max(0, nesting - 1);
      } else if (current == '=' && nesting == 0) {
        return type.substring(0, index).trim();
      }
    }
    return type;
  }

  private static String javaPrimitiveType(String type) {
    switch (type) {
      case "Int": case "kotlin.Int": return "int";
      case "Long": case "kotlin.Long": return "long";
      case "Double": case "kotlin.Double": return "double";
      case "Float": case "kotlin.Float": return "float";
      case "Boolean": case "kotlin.Boolean": return "boolean";
      case "Char": case "kotlin.Char": return "char";
      case "Byte": case "kotlin.Byte": return "byte";
      case "Short": case "kotlin.Short": return "short";
      default: return null;
    }
  }

  private static String boxedType(String primitive) {
    switch (primitive) {
      case "int": return "Integer";
      case "long": return "Long";
      case "double": return "Double";
      case "float": return "Float";
      case "boolean": return "Boolean";
      case "char": return "Character";
      case "byte": return "Byte";
      case "short": return "Short";
      default: return "Object";
    }
  }

  private static String javaPrimitiveArrayType(String type) {
    switch (type) {
      case "IntArray": case "kotlin.IntArray": return "int[]";
      case "LongArray": case "kotlin.LongArray": return "long[]";
      case "DoubleArray": case "kotlin.DoubleArray": return "double[]";
      case "FloatArray": case "kotlin.FloatArray": return "float[]";
      case "BooleanArray": case "kotlin.BooleanArray": return "boolean[]";
      case "CharArray": case "kotlin.CharArray": return "char[]";
      case "ByteArray": case "kotlin.ByteArray": return "byte[]";
      case "ShortArray": case "kotlin.ShortArray": return "short[]";
      default: return null;
    }
  }

  private static String javaUserType(String type) {
    if (!JAVA_TYPE_NAME_PATTERN.matcher(type).matches()) {
      return "Object";
    }
    final TypeResolutionContext context = TYPE_CONTEXT.get();
    if (context == null) {
      return "Object";
    }
    final String imported = context.imports.get(type);
    if (imported != null) {
      return imported;
    }
    if (type.indexOf('.') >= 0) {
      return type;
    }
    if (context.declaredTypes.containsKey(type) || context.knownSimpleTypes.containsKey(type)) {
      return type;
    }
    return "Object";
  }

  private static String javaCollectionType(String type) {
    switch (type) {
      case "List": case "MutableList": case "kotlin.collections.List":
      case "kotlin.collections.MutableList": return "java.util.List";
      case "Set": case "MutableSet": case "kotlin.collections.Set":
      case "kotlin.collections.MutableSet": return "java.util.Set";
      case "Map": case "MutableMap": case "kotlin.collections.Map":
      case "kotlin.collections.MutableMap": return "java.util.Map";
      case "Collection": case "MutableCollection": case "kotlin.collections.Collection":
      case "kotlin.collections.MutableCollection": return "java.util.Collection";
      case "Iterable": case "kotlin.collections.Iterable": return "java.lang.Iterable";
      default: return null;
    }
  }

  private static String javaTypeArgument(String kotlinArgument) {
    String argument = kotlinArgument.trim();
    if ("*".equals(argument)) return "?";
    if (argument.startsWith("out ")) {
      return "? extends " + boxedTypeArgument(javaType(argument.substring(4)));
    }
    if (argument.startsWith("in ")) {
      return "? super " + boxedTypeArgument(javaType(argument.substring(3)));
    }
    return boxedTypeArgument(javaType(argument));
  }

  private static String boxedTypeArgument(String javaType) {
    switch (javaType) {
      case "int":
      case "long":
      case "double":
      case "float":
      case "boolean":
      case "char":
      case "byte":
      case "short":
        return boxedType(javaType);
      default:
        return javaType;
    }
  }

  private static String javaReferenceArrayType(String kotlinElementType) {
    final String elementType = boxedTypeArgument(javaType(kotlinElementType));
    return "void".equals(elementType) || elementType.contains("?") ? "Object[]" : elementType + "[]";
  }

  private static TypeApplication parseTypeApplication(String type) {
    final int open = type.indexOf('<');
    if (open < 1 || !type.endsWith(">")) {
      return null;
    }
    final String rawType = type.substring(0, open).trim();
    final String argumentText = type.substring(open + 1, type.length() - 1);
    return new TypeApplication(rawType, splitParameters(argumentText));
  }

  private static final class TypeResolutionContext {
    final Map<String, String> imports;
    final Map<String, String> declaredTypes;
    final Map<String, String> knownSimpleTypes;

    private TypeResolutionContext(
        Map<String, String> imports,
        Map<String, String> declaredTypes,
        Map<String, String> knownSimpleTypes) {
      this.imports = imports;
      this.declaredTypes = declaredTypes;
      this.knownSimpleTypes = knownSimpleTypes;
    }

    static TypeResolutionContext create(
        String packageName, String source, Set<String> knownTypes) {
      final Map<String, String> imports = new LinkedHashMap<>();
      final Matcher importMatcher = KOTLIN_IMPORT_PATTERN.matcher(source);
      while (importMatcher.find()) {
        final String qualifiedName = importMatcher.group(1);
        if (importMatcher.group(2) != null) {
          continue;
        }
        final int separator = qualifiedName.lastIndexOf('.');
        final String importedName = importMatcher.group(3) != null
            ? importMatcher.group(3)
            : separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
        imports.put(importedName, qualifiedName);
      }

      final Map<String, String> declaredTypes = new LinkedHashMap<>();
      final List<KotlinJvmSyntaxParser.TopLevelTypeSyntax> syntaxTypes =
          KotlinJvmSyntaxParser.findTopLevelTypes(source);
      if (syntaxTypes != null) {
        for (KotlinJvmSyntaxParser.TopLevelTypeSyntax type : syntaxTypes) {
          if (!type.privateType && type.name != null) {
            declaredTypes.put(type.name, packageName.isEmpty()
                ? type.name : packageName + "." + type.name);
          }
        }
      }
      final Map<String, String> knownSimpleTypes = new LinkedHashMap<>();
      if (knownTypes != null) {
        for (String qualifiedType : knownTypes) {
          final int separator = qualifiedType.lastIndexOf('.');
          final String typePackage = separator < 0 ? "" : qualifiedType.substring(0, separator);
          final String simpleName =
              separator < 0 ? qualifiedType : qualifiedType.substring(separator + 1);
          if (packageName.equals(typePackage)) {
            knownSimpleTypes.put(simpleName, qualifiedType);
          }
        }
      }
      return new TypeResolutionContext(imports, declaredTypes, knownSimpleTypes);
    }
  }

  private static final class TypeApplication {
    final String rawType;
    final List<String> arguments;

    TypeApplication(String rawType, List<String> arguments) {
      this.rawType = rawType;
      this.arguments = arguments;
    }
  }

  private static String methodBody(String kotlinType) {
    return "void".equals(javaType(kotlinType))
        ? " {}\n"
        : " { return " + defaultValue(kotlinType) + "; }\n";
  }

  private static String defaultValue(String kotlinType) {
    final String type = javaType(kotlinType);
    if ("void".equals(type)) return "";
    if ("boolean".equals(type)) return "false";
    if ("char".equals(type)) return "'\\0'";
    if ("byte".equals(type) || "short".equals(type) || "int".equals(type)) return "0";
    if ("long".equals(type)) return "0L";
    if ("float".equals(type)) return "0F";
    if ("double".equals(type)) return "0D";
    return "null";
  }

  private static boolean isFacadeName(String simpleName, String kotlinFileName, String source) {
    final Matcher jvmName = FILE_JVM_NAME_PATTERN.matcher(source);
    if (jvmName.find()) {
      return simpleName.equals(jvmName.group(1));
    }
    if (kotlinFileName == null || !kotlinFileName.endsWith(".kt")) {
      return false;
    }
    final String fileBaseName = kotlinFileName.substring(0, kotlinFileName.length() - 3);
    return simpleName.equals(fileBaseName + "Kt");
  }

  private static StringBuilder header(String packageName) {
    final StringBuilder out = new StringBuilder();
    if (!packageName.isEmpty()) out.append("package ").append(packageName).append(";\n\n");
    return out;
  }

  private static boolean isPrivate(String modifiers) {
    return modifiers != null && Pattern.compile("(?:^|\\s)private(?:\\s|$)").matcher(modifiers).find();
  }

  private static String safeName(String name, int index) {
    return name.matches("[A-Za-z_$][\\w$]*") ? name : "arg" + index;
  }

  private static int matchingBrace(String source, int start) {
    int depth = 0;
    for (int i = start; i < source.length(); i++) {
      char current = source.charAt(i);
      if (current == '{') depth++;
      else if (current == '}' && --depth == 0) return i;
    }
    return -1;
  }

  private static int braceDelta(String line) {
    int delta = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int i = 0; i < line.length(); i++) {
      final char current = line.charAt(i);
      if (quoted) {
        if (current == '"' && !escaped) quoted = false;
        escaped = current == '\\' && !escaped;
      } else if (current == '"') quoted = true;
      else if (current == '{') delta++;
      else if (current == '}') delta--;
    }
    return delta;
  }
}
