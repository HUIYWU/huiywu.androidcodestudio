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

/** Extracts declaration boundaries from Kotlin source without performing semantic resolution. */
final class KotlinJvmSyntaxParser {

  private KotlinJvmSyntaxParser() {}

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
          final TSNode bodyNode = directChild(declaration, "class_body", "enum_class_body");
          final String body = bodyNode == null ? "" : innerBody(text(source, bodyNode));
          final TSNode constructor = directChild(declaration, "primary_constructor");
          final String constructorText = constructor == null ? null : text(source, constructor);
          final TSNode companion = bodyNode == null ? null : directChild(bodyNode, "companion_object");
          final TSNode companionBodyNode = companion == null ? null : directChild(companion, "class_body");
          final String companionBody =
              companionBodyNode == null ? null : innerBody(text(source, companionBodyNode));
          return new TypeSyntax(
              nodeType,
              declarationText,
              body,
              constructorText,
              companionBody,
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

  private static String text(String source, TSNode node) {
    // This Android binding parses Java strings as UTF-16; offsets are byte counts.
    final int start = Math.max(0, Math.min(source.length(), node.getStartByte() / 2));
    final int end = Math.max(start, Math.min(source.length(), node.getEndByte() / 2));
    return source.substring(start, end);
  }

  private static String innerBody(String body) {
    final int start = body.indexOf('{');
    final int end = body.lastIndexOf('}');
    return start >= 0 && end > start ? body.substring(start + 1, end) : "";
  }

  static final class TypeSyntax {
    final String nodeType;
    final String declarationText;
    final String body;
    final String constructorText;
    final String companionBody;
    final boolean interfaceType;
    final boolean enumType;
    final boolean annotationType;
    final boolean privateType;

    TypeSyntax(
        String nodeType,
        String declarationText,
        String body,
        String constructorText,
        String companionBody,
        boolean interfaceType,
        boolean enumType,
        boolean annotationType,
        boolean privateType) {
      this.nodeType = nodeType;
      this.declarationText = declarationText;
      this.body = body;
      this.constructorText = constructorText;
      this.companionBody = companionBody;
      this.interfaceType = interfaceType;
      this.enumType = enumType;
      this.annotationType = annotationType;
      this.privateType = privateType;
    }

    boolean objectType() {
      return "object_declaration".equals(nodeType);
    }
  }
}
