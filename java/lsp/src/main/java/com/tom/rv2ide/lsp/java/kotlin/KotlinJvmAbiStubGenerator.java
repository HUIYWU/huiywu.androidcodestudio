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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative source-level Kotlin-to-Java ABI projection used while compiler-provided Kotlin JVM
 * symbols are unavailable.
 *
 * <p>This is deliberately not a Kotlin parser. It supports common declarations and emits only
 * members whose JVM form is unambiguous enough for Java diagnostics. Unsupported signatures are
 * represented with {@link Object}; that preserves Java attribution without claiming false Kotlin
 * semantic precision.
 */
final class KotlinJvmAbiStubGenerator {

  private static final Pattern PACKAGE_PATTERN =
      Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)");
  private static final Pattern FILE_JVM_NAME_PATTERN =
      Pattern.compile("(?m)^\\s*@file:JvmName\\s*\\(\\s*\\\"([A-Za-z_$][\\w$]*)\\\"\\s*\\)");
  private static final Pattern TYPE_PATTERN =
      Pattern.compile(
          "(?m)^\\s*((?:(?:public|protected|internal|private|open|abstract|sealed|data|value)\\s+)*)"
              + "((?:enum\\s+class)|annotation\\s+class|class|interface|object)"
              + "\\s+([A-Za-z_][\\w]*)(?:\\s*<[^>{}()]*>)?\\s*(\\([^\\n{]*\\))?");
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

  private KotlinJvmAbiStubGenerator() {}

  static String generate(String qualifiedName, String kotlinFileName, String source) {
    final int separator = qualifiedName.lastIndexOf('.');
    final String packageName = separator < 0 ? "" : qualifiedName.substring(0, separator);
    final String simpleName = separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
    final String sourcePackage = packageMatcher.find() ? packageMatcher.group(1) : "";
    if (!packageName.equals(sourcePackage)) {
      return null;
    }

    final Matcher typeMatcher = TYPE_PATTERN.matcher(source);
    while (typeMatcher.find()) {
      if (simpleName.equals(typeMatcher.group(3)) && !isPrivate(typeMatcher.group(1))) {
        return generateType(packageName, simpleName, typeMatcher, source);
      }
    }
    return isFacadeName(simpleName, kotlinFileName, source)
        ? generateFacade(packageName, simpleName, source)
        : null;
  }

  private static String generateType(
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
    if (isObject) {
      out.append("  public static final ").append(simpleName).append(" INSTANCE = null;\n");
    } else if (!isInterface) {
      out.append("  public ").append(simpleName).append(parameterList(declaration.group(4))).append(" {}\n");
      appendConstructorProperties(out, declaration.group(4));
    }

    final int bodyStart = source.indexOf('{', declaration.end());
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
    appendMembers(out, source, false, true);
    out.append("}\n");
    return out.toString();
  }

  private static void appendCompanionMembers(StringBuilder out, String body) {
    final Matcher companion = Pattern.compile("(?s)companion\\s+object(?:\\s+[A-Za-z_][\\w]*)?\\s*\\{").matcher(body);
    if (!companion.find()) {
      return;
    }
    final int bodyStart = companion.end() - 1;
    final int bodyEnd = matchingBrace(body, bodyStart);
    if (bodyEnd <= bodyStart) {
      return;
    }
    final String companionBody = body.substring(bodyStart + 1, bodyEnd);
    // Kotlin exposes the companion itself as Foo.Companion. Keep that normal JVM surface in
    // addition to the direct host-class methods created only for @JvmStatic declarations.
    out.append("  public static final class Companion {\n");
    appendMembers(
        out, companionBody.replace("@JvmStatic", "").replace("@JvmField", ""), false, false);
    out.append("  }\n");
    out.append("  public static final Companion Companion = null;\n");
    final String[] lines = companionBody.split("\\R");
    boolean jvmStatic = false;
    boolean jvmField = false;
    for (String line : lines) {
      final boolean hasJvmStatic = line.contains("@JvmStatic");
      final boolean hasJvmField = line.contains("@JvmField");
      final String declarationLine =
          line.replace("@JvmStatic", "").replace("@JvmField", "").trim();
      if (hasJvmStatic) {
        jvmStatic = true;
      }
      if (hasJvmField) {
        jvmField = true;
      }
      final Matcher function = FUNCTION_PATTERN.matcher(declarationLine);
      if (jvmStatic && function.matches() && !isPrivate(function.group(1))) {
        appendStaticFunction(out, function);
        jvmStatic = false;
        continue;
      }
      final Matcher property = PROPERTY_PATTERN.matcher(declarationLine);
      if (property.matches() && !isPrivate(property.group(1))) {
        if (jvmField) {
          appendStaticField(out, property);
        } else if (jvmStatic) {
          appendStaticProperty(out, property);
        }
        jvmStatic = false;
        jvmField = false;
        continue;
      }
      if (!declarationLine.isEmpty() && !declarationLine.startsWith("@")) {
        jvmStatic = false;
        jvmField = false;
      }
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

  private static void appendStaticField(StringBuilder out, Matcher property) {
    out.append("  public static ").append(javaType(property.group(4))).append(' ')
        .append(property.group(3)).append(";\n");
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

  private static void appendConstructorProperties(StringBuilder out, String kotlinParameters) {
    if (kotlinParameters == null || kotlinParameters.length() < 2) {
      return;
    }
    for (String raw : kotlinParameters.substring(1, kotlinParameters.length() - 1).split(",")) {
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

  private static String parameterList(String kotlinParameters) {
    if (kotlinParameters == null || kotlinParameters.length() < 2) {
      return "()";
    }
    return javaParameterList(
        splitParameters(kotlinParameters.substring(1, kotlinParameters.length() - 1)));
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
    String type = kotlinType.trim().replace("?", "");
    final int equals = type.indexOf('=');
    if (equals >= 0) {
      type = type.substring(0, equals).trim();
    }
    if (type.startsWith("String")) return "String";
    if (type.startsWith("Int")) return "int";
    if (type.startsWith("Long")) return "long";
    if (type.startsWith("Double")) return "double";
    if (type.startsWith("Float")) return "float";
    if (type.startsWith("Boolean")) return "boolean";
    if (type.startsWith("Char")) return "char";
    if (type.startsWith("Byte")) return "byte";
    if (type.startsWith("Short")) return "short";
    if (type.startsWith("Unit")) return "void";
    if (type.startsWith("Any") || type.startsWith("Nothing")) return "Object";
    return "Object";
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
