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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.java.kotlin;

import com.tom.rv2ide.treesitter.TSNode;
import com.tom.rv2ide.treesitter.TSParser;
import com.tom.rv2ide.treesitter.kotlin.TSLanguageKotlin;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Extracts declaration boundaries from Kotlin source without performing semantic resolution. */
final class KotlinJvmSyntaxParser {

  private KotlinJvmSyntaxParser() {}

  static List<TopLevelTypeSyntax> findTopLevelTypes(String source) {
    if (source == null) {
      return null;
    }
    try (TSParser parser = TSParser.create()) {
      parser.setLanguage(TSLanguageKotlin.getInstance());
      try (var tree = parser.parseString(source)) {
        if (tree == null) {
          return null;
        }
        final TSNode root = tree.getRootNode();
        if (root == null || root.isNull()) {
          return null;
        }
        final List<TopLevelTypeSyntax> result = new ArrayList<>();
        for (int index = 0; index < root.getNamedChildCount(); index++) {
          final TSNode declaration = root.getNamedChild(index);
          final String kind = declaration.getType();
          if (!"class_declaration".equals(kind) && !"object_declaration".equals(kind)) {
            continue;
          }
          final TSNode name = directChild(declaration, "type_identifier");
          if (name != null) {
            result.add(new TopLevelTypeSyntax(
                text(source, name),
                startIndex(source, name),
                endIndex(source, name) - startIndex(source, name),
                hasModifier(declaration, "private")));
          }
        }
        return Collections.unmodifiableList(result);
      }
    } catch (Throwable ignored) {
      return null;
    }
  }

  static List<MemberSyntax> findTopLevelMembers(String source) {
    if (source == null) {
      return null;
    }
    try (TSParser parser = TSParser.create()) {
      parser.setLanguage(TSLanguageKotlin.getInstance());
      try (var tree = parser.parseString(source)) {
        if (tree == null) {
          return null;
        }
        final TSNode root = tree.getRootNode();
        return root == null || root.isNull() ? null : members(source, root);
      }
    } catch (Throwable ignored) {
      return null;
    }
  }

  static TypeSyntax findTopLevelType(String source, String simpleName) {
    if (source == null || simpleName == null || simpleName.isEmpty()) {
      return null;
    }
    try (TSParser parser = TSParser.create()) {
      parser.setLanguage(TSLanguageKotlin.getInstance());
      try (var tree = parser.parseString(source)) {
        if (tree == null) {
          return null;
        }
        final TSNode root = tree.getRootNode();
        if (root == null || root.isNull()) {
          return null;
        }
        for (int index = 0; index < root.getNamedChildCount(); index++) {
          final TSNode declaration = root.getNamedChild(index);
          final String nodeType = declaration.getType();
          if (!"class_declaration".equals(nodeType) && !"object_declaration".equals(nodeType)) {
            continue;
          }
          final TSNode nameNode = directChild(declaration, "type_identifier");
          if (nameNode == null || !simpleName.equals(text(source, nameNode))) {
            continue;
          }
          final String declarationText = text(source, declaration);
          final List<TypeParameterSyntax> typeParameters =
              typeParameters(source, directChild(declaration, "type_parameters"));
          final List<SuperTypeSyntax> superTypes = superTypes(source, declaration);
          final TSNode bodyNode = directChild(declaration, "class_body", "enum_class_body");
          final String body = bodyNode == null ? "" : innerBody(text(source, bodyNode));
          final List<MemberSyntax> members = members(source, bodyNode);
          final TSNode constructor = constructorNode(declaration);
          final String recoveredConstructorText =
              constructorTextFallback(source, declaration, nameNode, bodyNode);
          final String constructorText = constructor == null
              ? recoveredConstructorText
              : text(source, constructor);
          final List<ConstructorParameterSyntax> parsedConstructorParameters =
              constructorParameters(source, constructor);
          final String parameterFallbackText =
              recoveredConstructorText == null ? constructorText : recoveredConstructorText;
          final List<ConstructorParameterSyntax> constructorParameters =
              parsedConstructorParameters.isEmpty() && parameterFallbackText != null
                  ? constructorParametersFallback(parameterFallbackText)
                  : parsedConstructorParameters;
          final TSNode companion = bodyNode == null ? null : directChild(bodyNode, "companion_object");
          final TSNode companionBodyNode = companion == null ? null : directChild(companion, "class_body");
          final String companionBody =
              companionBodyNode == null ? null : innerBody(text(source, companionBodyNode));
          final List<MemberSyntax> companionMembers = members(source, companionBodyNode);
          return new TypeSyntax(
              nodeType,
              declarationText,
              body,
              members,
              typeParameters,
              superTypes,
              constructorParameters,
              companionBody,
              companionMembers,
              hasDirectToken(declaration, "interface"),
              hasDirectToken(declaration, "enum"),
              hasModifier(declaration, "annotation"),
              hasModifier(declaration, "private"));
        }
      }
    } catch (Throwable ignored) {
      // Tree-sitter is an accuracy layer. Callers retain a conservative text fallback for devices
      // where the native grammar cannot be loaded or an incomplete edit cannot be parsed safely.
    }
    return null;
  }

  private static List<MemberSyntax> members(String source, TSNode body) {
    if (body == null) {
      return Collections.emptyList();
    }
    final List<MemberSyntax> result = new ArrayList<>();
    for (int index = 0; index < body.getNamedChildCount(); index++) {
      final TSNode declaration = body.getNamedChild(index);
      final String kind = declaration.getType();
      if (!"function_declaration".equals(kind) && !"property_declaration".equals(kind)) {
        continue;
      }
      final TSNode modifiers = directChild(declaration, "modifiers");
      final TSNode functionBody = directChild(declaration, "function_body");
      final int declarationStart = modifiers == null ? startIndex(source, declaration) : endIndex(source, modifiers);
      final int declarationEnd = functionBody == null
          ? endIndex(source, declaration)
          : startIndex(source, functionBody);
      final String modifierText = modifiers == null ? "" : text(source, modifiers);
      final String declarationText = source.substring(
          Math.max(0, Math.min(source.length(), declarationStart)),
          Math.max(declarationStart, Math.min(source.length(), declarationEnd)))
          .replaceAll("\\s+", " ")
          .trim();
      result.add("function_declaration".equals(kind)
          ? functionSyntax(source, declaration, declarationText, modifiers, modifierText)
          : propertySyntax(source, declaration, declarationText, modifiers, modifierText));
    }
    return Collections.unmodifiableList(result);
  }

  private static MemberSyntax functionSyntax(
      String source,
      TSNode declaration,
      String declarationText,
      TSNode modifiers,
      String modifierText) {
    final TSNode name = directChild(declaration, "simple_identifier");
    final List<TypeParameterSyntax> typeParameters =
        typeParameters(source, directChild(declaration, "type_parameters"));
    final TSNode parameters = directChild(declaration, "function_value_parameters");
    final TSNode receiver = typeChildBefore(declaration, name);
    final TSNode returnType = typeChildAfter(declaration, parameters);
    final List<ParameterSyntax> parameterList = parameters(source, parameters);
    return new MemberSyntax(
        "function_declaration",
        declarationText,
        containsToken(modifiers, "private"),
        modifierText.contains("JvmStatic"),
        modifierText.contains("JvmField"),
        modifierText.contains("JvmOverloads"),
        name == null ? null : text(source, name),
        parameters == null ? "()" : text(source, parameters),
        parameterList,
        typeParameters,
        receiver == null ? null : text(source, receiver),
        returnType == null ? null : text(source, returnType),
        false,
        false);
  }

  private static MemberSyntax propertySyntax(
      String source,
      TSNode declaration,
      String declarationText,
      TSNode modifiers,
      String modifierText) {
    final TSNode variable = directChild(declaration, "variable_declaration");
    final TSNode name = variable == null ? null : directChild(variable, "simple_identifier");
    final TSNode propertyType = variable == null ? null : firstTypeChild(variable);
    final TSNode receiver = variable == null ? null : typeChildBefore(declaration, variable);
    return new MemberSyntax(
        "property_declaration",
        declarationText,
        containsToken(modifiers, "private"),
        modifierText.contains("JvmStatic"),
        modifierText.contains("JvmField"),
        modifierText.contains("JvmOverloads"),
        name == null ? null : text(source, name),
        null,
        Collections.emptyList(),
        Collections.emptyList(),
        receiver == null ? null : text(source, receiver),
        propertyType == null ? null : text(source, propertyType),
        hasDirectToken(declaration, "var"),
        hasDirectToken(declaration, "val"));
  }

  private static List<SuperTypeSyntax> superTypes(String source, TSNode declaration) {
    final List<SuperTypeSyntax> result = new ArrayList<>();
    for (int index = 0; index < declaration.getNamedChildCount(); index++) {
      final TSNode child = declaration.getNamedChild(index);
      if ("class_body".equals(child.getType()) || "enum_class_body".equals(child.getType())) {
        break;
      }
      collectSuperTypes(source, child, result);
    }
    return Collections.unmodifiableList(result);
  }

  private static void collectSuperTypes(
      String source, TSNode node, List<SuperTypeSyntax> result) {
    if ("delegation_specifier".equals(node.getType())) {
      final TSNode invocation = firstDescendant(node, "constructor_invocation");
      final TSNode userType = firstDescendant(node, "user_type");
      if (userType != null) {
        result.add(new SuperTypeSyntax(text(source, userType), invocation != null));
      }
      return;
    }
    for (int index = 0; index < node.getNamedChildCount(); index++) {
      collectSuperTypes(source, node.getNamedChild(index), result);
    }
  }

  private static List<TypeParameterSyntax> typeParameters(String source, TSNode container) {
    if (container == null) {
      return Collections.emptyList();
    }
    final List<TypeParameterSyntax> result = new ArrayList<>();
    for (int index = 0; index < container.getNamedChildCount(); index++) {
      final TSNode parameter = container.getNamedChild(index);
      if (!"type_parameter".equals(parameter.getType())) {
        continue;
      }
      final TSNode name = directChild(parameter, "type_identifier");
      final TSNode bound = typeChildAfter(parameter, name);
      if (name != null) {
        result.add(new TypeParameterSyntax(
            text(source, name), bound == null ? null : text(source, bound)));
      }
    }
    return Collections.unmodifiableList(result);
  }

  private static List<ConstructorParameterSyntax> constructorParameters(
      String source, TSNode constructor) {
    if (constructor == null) {
      return Collections.emptyList();
    }
    final List<ConstructorParameterSyntax> result = new ArrayList<>();
    final List<TSNode> parameters = new ArrayList<>();
    collectDescendants(constructor, "class_parameter", parameters);
    for (TSNode parameter : parameters) {
      final TSNode name = directChild(parameter, "simple_identifier");
      final TSNode type = firstTypeChild(parameter);
      result.add(new ConstructorParameterSyntax(
          name == null ? "arg" + result.size() : text(source, name),
          type == null ? null : text(source, type),
          hasDirectToken(parameter, "val"),
          hasDirectToken(parameter, "var")));
    }
    return Collections.unmodifiableList(result);
  }

  private static List<ParameterSyntax> parameters(String source, TSNode container) {
    if (container == null) {
      return Collections.emptyList();
    }
    final List<TSNode> nodes = new ArrayList<>();
    for (int index = 0; index < container.getNamedChildCount(); index++) {
      final TSNode child = container.getNamedChild(index);
      if ("parameter".equals(child.getType())) {
        nodes.add(child);
      }
    }
    final List<ParameterSyntax> result = new ArrayList<>();
    for (int index = 0; index < nodes.size(); index++) {
      final TSNode parameter = nodes.get(index);
      final TSNode name = directChild(parameter, "simple_identifier");
      final TSNode type = firstTypeChild(parameter);
      final int suffixEnd = index + 1 < nodes.size()
          ? startIndex(source, nodes.get(index + 1))
          : endIndex(source, container) - 1;
      final int suffixStart = endIndex(source, parameter);
      final boolean defaultValue = suffixEnd > suffixStart
          && source.substring(suffixStart, suffixEnd).indexOf('=') >= 0;
      result.add(new ParameterSyntax(
          name == null ? "arg" + index : text(source, name),
          type == null ? null : text(source, type),
          defaultValue));
    }
    return Collections.unmodifiableList(result);
  }

  private static TSNode typeChildBefore(TSNode parent, TSNode boundary) {
    if (boundary == null) {
      return null;
    }
    for (int index = 0; index < parent.getNamedChildCount(); index++) {
      final TSNode child = parent.getNamedChild(index);
      if (sameNode(child, boundary)) {
        break;
      }
      if (isTypeNode(child.getType())) {
        return child;
      }
    }
    return null;
  }

  private static TSNode typeChildAfter(TSNode parent, TSNode boundary) {
    if (boundary == null) {
      return null;
    }
    boolean after = false;
    for (int index = 0; index < parent.getNamedChildCount(); index++) {
      final TSNode child = parent.getNamedChild(index);
      if (after && isTypeNode(child.getType())) {
        return child;
      }
      if (sameNode(child, boundary)) {
        after = true;
      }
    }
    return null;
  }

  private static TSNode firstTypeChild(TSNode parent) {
    for (int index = 0; index < parent.getNamedChildCount(); index++) {
      final TSNode child = parent.getNamedChild(index);
      if (isTypeNode(child.getType())) {
        return child;
      }
    }
    return null;
  }

  private static boolean isTypeNode(String type) {
    return "user_type".equals(type)
        || "nullable_type".equals(type)
        || "function_type".equals(type)
        || "parenthesized_type".equals(type)
        || "type_identifier".equals(type)
        || "not_nullable_type".equals(type);
  }

  private static boolean sameNode(TSNode first, TSNode second) {
    return first.getStartByte() == second.getStartByte()
        && first.getEndByte() == second.getEndByte()
        && first.getType().equals(second.getType());
  }

  private static TSNode directChild(TSNode parent, String... types) {
    for (int index = 0; index < parent.getNamedChildCount(); index++) {
      final TSNode child = parent.getNamedChild(index);
      final String childType = child.getType();
      for (String type : types) {
        if (type.equals(childType)) {
          return child;
        }
      }
    }
    return null;
  }

  private static String constructorTextFallback(
      String source, TSNode declaration, TSNode name, TSNode body) {
    final int searchStart = endIndex(source, name);
    final int searchEnd = body == null
        ? endIndex(source, declaration)
        : startIndex(source, body);
    final int open = source.indexOf('(', searchStart);
    if (open < 0 || open >= searchEnd) {
      return null;
    }
    int depth = 0;
    for (int index = open; index < searchEnd; index++) {
      final char current = source.charAt(index);
      if (current == '(') {
        depth++;
      } else if (current == ')' && --depth == 0) {
        return source.substring(open, index + 1);
      }
    }
    return null;
  }

  private static List<ConstructorParameterSyntax> constructorParametersFallback(String constructorText) {
    if (constructorText.length() < 2) {
      return Collections.emptyList();
    }
    final List<ConstructorParameterSyntax> result = new ArrayList<>();
    for (String rawParameter : splitTopLevel(constructorText.substring(1, constructorText.length() - 1))) {
      String parameter = rawParameter.trim();
      final int colon = topLevelIndexOf(parameter, ':');
      if (colon < 1) {
        continue;
      }
      String declaration = parameter.substring(0, colon).trim();
      final boolean mutable = declaration.matches("(?s).*\\bvar\\s+[A-Za-z_$][\\w$]*$");
      final boolean property = mutable
          || declaration.matches("(?s).*\\bval\\s+[A-Za-z_$][\\w$]*$");
      declaration = declaration.replaceFirst("(?s)^.*\\b(?:val|var)\\s+", "").trim();
      final String[] nameParts = declaration.split("\\s+");
      final String name = nameParts.length == 0 ? "arg" + result.size() : nameParts[nameParts.length - 1];
      String type = parameter.substring(colon + 1).trim();
      final int equals = topLevelIndexOf(type, '=');
      if (equals >= 0) {
        type = type.substring(0, equals).trim();
      }
      result.add(new ConstructorParameterSyntax(name, type, property, mutable));
    }
    return Collections.unmodifiableList(result);
  }

  private static List<String> splitTopLevel(String text) {
    final List<String> result = new ArrayList<>();
    int start = 0;
    int nesting = 0;
    for (int index = 0; index < text.length(); index++) {
      final char current = text.charAt(index);
      if (current == '<' || current == '(' || current == '[' || current == '{') {
        nesting++;
      } else if (current == '>' || current == ')' || current == ']' || current == '}') {
        nesting = Math.max(0, nesting - 1);
      } else if (current == ',' && nesting == 0) {
        result.add(text.substring(start, index));
        start = index + 1;
      }
    }
    if (start < text.length()) {
      result.add(text.substring(start));
    }
    return result;
  }

  private static int topLevelIndexOf(String text, char target) {
    int nesting = 0;
    for (int index = 0; index < text.length(); index++) {
      final char current = text.charAt(index);
      if (current == '<' || current == '(' || current == '[' || current == '{') {
        nesting++;
      } else if (current == '>' || current == ')' || current == ']' || current == '}') {
        nesting = Math.max(0, nesting - 1);
      } else if (current == target && nesting == 0) {
        return index;
      }
    }
    return -1;
  }

  private static TSNode constructorNode(TSNode declaration) {
    final TSNode direct = directChild(declaration, "primary_constructor");
    if (direct != null) {
      return direct;
    }
    final TSNode body = directChild(declaration, "class_body", "enum_class_body");
    final int bodyStart = body == null ? Integer.MAX_VALUE : body.getStartByte();
    for (int index = 0; index < declaration.getNamedChildCount(); index++) {
      final TSNode child = declaration.getNamedChild(index);
      if (child.getStartByte() >= bodyStart) {
        break;
      }
      final TSNode recovered = firstDescendant(child, "primary_constructor");
      if (recovered != null) {
        return recovered;
      }
      if (firstDescendant(child, "class_parameter") != null) {
        return child;
      }
    }
    return null;
  }

  private static TSNode firstDescendant(TSNode node, String type) {
    if (type.equals(node.getType())) {
      return node;
    }
    for (int index = 0; index < node.getNamedChildCount(); index++) {
      final TSNode found = firstDescendant(node.getNamedChild(index), type);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static void collectDescendants(TSNode node, String type, List<TSNode> result) {
    if (type.equals(node.getType())) {
      result.add(node);
      return;
    }
    for (int index = 0; index < node.getNamedChildCount(); index++) {
      collectDescendants(node.getNamedChild(index), type, result);
    }
  }

  private static boolean hasDirectToken(TSNode parent, String token) {
    for (int index = 0; index < parent.getChildCount(); index++) {
      final TSNode child = parent.getChild(index);
      if (token.equals(child.getType())) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasModifier(TSNode declaration, String modifier) {
    final TSNode modifiers = directChild(declaration, "modifiers");
    return modifiers != null && containsToken(modifiers, modifier);
  }

  private static boolean containsToken(TSNode node, String token) {
    if (node == null) {
      return false;
    }
    if (token.equals(node.getType())) {
      return true;
    }
    for (int index = 0; index < node.getChildCount(); index++) {
      if (containsToken(node.getChild(index), token)) {
        return true;
      }
    }
    return false;
  }

  private static int startIndex(String source, TSNode node) {
    return Math.max(0, Math.min(source.length(), node.getStartByte() / 2));
  }

  private static int endIndex(String source, TSNode node) {
    return Math.max(0, Math.min(source.length(), node.getEndByte() / 2));
  }

  private static String text(String source, TSNode node) {
    // This Android binding parses Java strings as UTF-16; offsets are byte counts.
    final int start = startIndex(source, node);
    final int end = Math.max(start, endIndex(source, node));
    return source.substring(start, end);
  }

  private static String innerBody(String body) {
    final int start = body.indexOf('{');
    final int end = body.lastIndexOf('}');
    return start >= 0 && end > start ? body.substring(start + 1, end) : "";
  }
  static final class TopLevelTypeSyntax {
    final String name;
    final int nameOffset;
    final int nameLength;
    final boolean privateType;

    TopLevelTypeSyntax(String name, int nameOffset, int nameLength, boolean privateType) {
      this.name = name;
      this.nameOffset = nameOffset;
      this.nameLength = nameLength;
      this.privateType = privateType;
    }
  }

  static final class TypeSyntax {

    final String nodeType;
    final String declarationText;
    final String body;
    final List<MemberSyntax> members;
    final List<TypeParameterSyntax> typeParameters;
    final List<SuperTypeSyntax> superTypes;
    final List<ConstructorParameterSyntax> constructorParameters;
    final String companionBody;
    final List<MemberSyntax> companionMembers;
    final boolean interfaceType;
    final boolean enumType;
    final boolean annotationType;
    final boolean privateType;

    TypeSyntax(
        String nodeType,
        String declarationText,
        String body,
        List<MemberSyntax> members,
        List<TypeParameterSyntax> typeParameters,
        List<SuperTypeSyntax> superTypes,
        List<ConstructorParameterSyntax> constructorParameters,
        String companionBody,
        List<MemberSyntax> companionMembers,
        boolean interfaceType,
        boolean enumType,
        boolean annotationType,
        boolean privateType) {
      this.nodeType = nodeType;
      this.declarationText = declarationText;
      this.body = body;
      this.members = members;
      this.typeParameters = typeParameters;
      this.superTypes = superTypes;
      this.constructorParameters = constructorParameters;
      this.companionBody = companionBody;
      this.companionMembers = companionMembers;
      this.interfaceType = interfaceType;
      this.enumType = enumType;
      this.annotationType = annotationType;
      this.privateType = privateType;
    }

    boolean objectType() {
      return "object_declaration".equals(nodeType);
    }
  }

  static final class MemberSyntax {
    final String kind;
    final String declarationText;
    final boolean privateMember;
    final boolean jvmStatic;
    final boolean jvmField;
    final boolean jvmOverloads;
    final String name;
    final String parameters;
    final List<ParameterSyntax> parameterList;
    final List<TypeParameterSyntax> typeParameters;
    final String receiverType;
    final String declaredType;
    final boolean mutableProperty;
    final boolean readOnlyProperty;

    MemberSyntax(
        String kind,
        String declarationText,
        boolean privateMember,
        boolean jvmStatic,
        boolean jvmField,
        boolean jvmOverloads,
        String name,
        String parameters,
        List<ParameterSyntax> parameterList,
        List<TypeParameterSyntax> typeParameters,
        String receiverType,
        String declaredType,
        boolean mutableProperty,
        boolean readOnlyProperty) {
      this.kind = kind;
      this.declarationText = declarationText;
      this.privateMember = privateMember;
      this.jvmStatic = jvmStatic;
      this.jvmField = jvmField;
      this.jvmOverloads = jvmOverloads;
      this.name = name;
      this.parameters = parameters;
      this.parameterList = parameterList;
      this.typeParameters = typeParameters;
      this.receiverType = receiverType;
      this.declaredType = declaredType;
      this.mutableProperty = mutableProperty;
      this.readOnlyProperty = readOnlyProperty;
    }

    boolean function() {
      return "function_declaration".equals(kind);
    }
  }

  static final class SuperTypeSyntax {
    final String type;
    final boolean constructorInvocation;

    SuperTypeSyntax(String type, boolean constructorInvocation) {
      this.type = type;
      this.constructorInvocation = constructorInvocation;
    }
  }

  static final class TypeParameterSyntax {
    final String name;
    final String upperBound;

    TypeParameterSyntax(String name, String upperBound) {
      this.name = name;
      this.upperBound = upperBound;
    }
  }

  static final class ConstructorParameterSyntax {
    final String name;
    final String type;
    final boolean property;
    final boolean mutableProperty;

    ConstructorParameterSyntax(
        String name, String type, boolean readOnlyProperty, boolean mutableProperty) {
      this.name = name;
      this.type = type;
      this.property = readOnlyProperty || mutableProperty;
      this.mutableProperty = mutableProperty;
    }
  }

  static final class ParameterSyntax {
    final String name;
    final String type;
    final boolean defaultValue;

    ParameterSyntax(String name, String type, boolean defaultValue) {
      this.name = name;
      this.type = type;
      this.defaultValue = defaultValue;
    }
  }
}
