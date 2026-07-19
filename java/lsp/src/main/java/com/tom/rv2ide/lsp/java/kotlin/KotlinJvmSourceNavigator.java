/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.java.kotlin;

import com.tom.rv2ide.lsp.java.kotlin.KotlinJvmTypeIndex.KotlinTypeDeclaration;
import com.tom.rv2ide.models.Location;
import com.tom.rv2ide.models.Position;
import com.tom.rv2ide.models.Range;
import com.tom.rv2ide.projects.FileManager;
import com.tom.rv2ide.projects.ModuleProject;
import java.nio.file.Path;
import java.util.List;
import jdkx.lang.model.element.Element;
import jdkx.lang.model.element.ElementKind;
import jdkx.lang.model.element.ExecutableElement;
import jdkx.lang.model.element.TypeElement;

/** Maps javac elements from Kotlin ABI stubs back to real Kotlin source declarations. */
public final class KotlinJvmSourceNavigator {

  private KotlinJvmSourceNavigator() {}

  public static Location find(ModuleProject module, Element element) {
    TypeElement owner = ownerType(element);
    if (module == null || owner == null) {
      return null;
    }
    final boolean companionOwner = "Companion".contentEquals(owner.getSimpleName())
        && owner.getEnclosingElement() instanceof TypeElement;
    if (companionOwner) {
      owner = (TypeElement) owner.getEnclosingElement();
    }
    final String qualifiedName = owner.getQualifiedName().toString();
    final KotlinTypeDeclaration declaration =
        KotlinJvmTypeIndex.findDeclaration(module, qualifiedName);
    if (declaration == null) {
      return null;
    }
    final String source = FileManager.INSTANCE.getDocumentContents(declaration.file).toString();
    if (element instanceof TypeElement) {
      return location(declaration.file, source, declaration.offset, declaration.length);
    }

    final String simpleName = owner.getSimpleName().toString();
    final KotlinJvmSyntaxParser.TypeSyntax type =
        KotlinJvmSyntaxParser.findTopLevelType(source, simpleName);
    final SourceRange range = type == null
        ? findFacadeMember(source, element)
        : companionOwner
            ? findMember(type.companionMembers, element, false)
            : findTypeMember(type, declaration, element);
    return range == null
        ? location(declaration.file, source, declaration.offset, declaration.length)
        : location(declaration.file, source, range.offset, range.length);
  }

  private static SourceRange findTypeMember(
      KotlinJvmSyntaxParser.TypeSyntax type,
      KotlinTypeDeclaration typeDeclaration,
      Element element) {
    if (element.getKind() == ElementKind.CONSTRUCTOR && element instanceof ExecutableElement) {
      if (KotlinAbiSyntheticMembers.isSyntheticConstructor(element)) {
        return null;
      }
      final int parameterCount = ((ExecutableElement) element).getParameters().size();
      SourceRange match = null;
      int matches = 0;
      for (KotlinJvmSyntaxParser.ConstructorSyntax constructor : type.secondaryConstructors) {
        if (constructorArityMatches(
            constructor.parameters, constructor.jvmOverloads, parameterCount)) {
          match = new SourceRange(constructor.nameOffset, constructor.nameLength);
          matches++;
        }
      }
      if (type.primaryConstructorPresent
          && primaryConstructorArityMatches(type, parameterCount)) {
        match = new SourceRange(typeDeclaration.offset, typeDeclaration.length);
        matches++;
      }
      return matches == 1
          ? match
          : new SourceRange(typeDeclaration.offset, typeDeclaration.length);
    }

    SourceRange range = findMember(type.members, element, false);
    if (range != null) {
      return range;
    }
    range = findConstructorProperty(type.constructorParameters, element);
    if (range != null) {
      return range;
    }
    return findMember(type.companionMembers, element, true);
  }

  private static SourceRange findConstructorProperty(
      List<KotlinJvmSyntaxParser.ConstructorParameterSyntax> parameters, Element element) {
    final String javaName = element.getSimpleName().toString();
    for (KotlinJvmSyntaxParser.ConstructorParameterSyntax parameter : parameters) {
      if (!parameter.property || parameter.nameOffset < 0 || parameter.name.isEmpty()) {
        continue;
      }
      final String accessor =
          Character.toUpperCase(parameter.name.charAt(0)) + parameter.name.substring(1);
      if (javaName.equals("get" + accessor)
          || (parameter.mutableProperty && javaName.equals("set" + accessor))) {
        return new SourceRange(parameter.nameOffset, parameter.nameLength);
      }
    }
    return null;
  }

  private static SourceRange findFacadeMember(String source, Element element) {
    final List<KotlinJvmSyntaxParser.MemberSyntax> members =
        KotlinJvmSyntaxParser.findTopLevelMembers(source);
    return members == null ? null : findMember(members, element, false);
  }

  private static SourceRange findMember(
      List<KotlinJvmSyntaxParser.MemberSyntax> members, Element element, boolean requireJvmStatic) {
    final String javaName = element.getSimpleName().toString();
    final int parameterCount = element instanceof ExecutableElement
        ? ((ExecutableElement) element).getParameters().size()
        : -1;
    SourceRange match = null;
    int matches = 0;
    for (KotlinJvmSyntaxParser.MemberSyntax member : members) {
      if (member.name == null || member.nameOffset < 0 || member.privateMember) {
        continue;
      }
      if (requireJvmStatic && !member.jvmStatic && !member.jvmField) {
        continue;
      }
      final boolean matchesElement = member.function()
          ? javaName.equals(member.name) && functionArityMatches(member, parameterCount)
          : propertyJavaNameMatches(member, javaName, element.getKind());
      if (matchesElement) {
        match = new SourceRange(member.nameOffset, member.nameLength);
        matches++;
      }
    }
    return matches == 1 ? match : null;
  }

  private static boolean functionArityMatches(
      KotlinJvmSyntaxParser.MemberSyntax member, int parameterCount) {
    final int fullCount = member.parameterList.size();
    if (parameterCount == fullCount) {
      return true;
    }
    if (!member.jvmOverloads || parameterCount < 0 || parameterCount >= fullCount) {
      return false;
    }
    int firstOmittable = fullCount;
    for (int index = fullCount - 1;
        index >= 0 && member.parameterList.get(index).defaultValue; index--) {
      firstOmittable = index;
    }
    return parameterCount >= firstOmittable;
  }

  private static boolean propertyJavaNameMatches(
      KotlinJvmSyntaxParser.MemberSyntax member, String javaName, ElementKind kind) {
    if (kind == ElementKind.FIELD && javaName.equals(member.name)) {
      return true;
    }
    if (kind != ElementKind.METHOD || member.name.isEmpty()) {
      return false;
    }
    final String accessor =
        Character.toUpperCase(member.name.charAt(0)) + member.name.substring(1);
    return javaName.equals("get" + accessor)
        || (member.mutableProperty && javaName.equals("set" + accessor));
  }

  private static boolean primaryConstructorArityMatches(
      KotlinJvmSyntaxParser.TypeSyntax type, int parameterCount) {
    final int fullCount = type.constructorParameters.size();
    if (parameterCount == fullCount) {
      return true;
    }
    if (!type.constructorJvmOverloads || parameterCount < 0 || parameterCount >= fullCount) {
      return false;
    }
    int firstOmittable = fullCount;
    for (int index = fullCount - 1;
        index >= 0 && type.constructorParameters.get(index).defaultValue; index--) {
      firstOmittable = index;
    }
    return parameterCount >= firstOmittable;
  }

  private static boolean constructorArityMatches(
      List<KotlinJvmSyntaxParser.ParameterSyntax> parameters,
      boolean jvmOverloads,
      int parameterCount) {
    if (parameterCount == parameters.size()) {
      return true;
    }
    if (!jvmOverloads || parameterCount < 0 || parameterCount >= parameters.size()) {
      return false;
    }
    int firstOmittable = parameters.size();
    for (int index = parameters.size() - 1;
        index >= 0 && parameters.get(index).defaultValue; index--) {
      firstOmittable = index;
    }
    return parameterCount >= firstOmittable;
  }

  private static TypeElement ownerType(Element element) {
    Element current = element;
    while (current != null && !(current instanceof TypeElement)) {
      current = current.getEnclosingElement();
    }
    return current instanceof TypeElement ? (TypeElement) current : null;
  }

  private static Location location(Path file, String source, int offset, int length) {
    final Position start = positionAt(source, offset);
    final Position end = positionAt(source, offset + Math.max(1, length));
    return new Location(file, new Range(start, end));
  }

  private static Position positionAt(String source, int offset) {
    final int bounded = Math.max(0, Math.min(offset, source.length()));
    int line = 0;
    int lineStart = 0;
    for (int index = 0; index < bounded; index++) {
      if (source.charAt(index) == '\n') {
        line++;
        lineStart = index + 1;
      }
    }
    return new Position(line, bounded - lineStart);
  }

  private static final class SourceRange {
    final int offset;
    final int length;

    SourceRange(int offset, int length) {
      this.offset = offset;
      this.length = length;
    }
  }
}