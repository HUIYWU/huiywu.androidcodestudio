/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.java.kotlin;

import com.tom.rv2ide.lsp.java.kotlin.KotlinJvmTypeIndex.KotlinTypeDeclaration;
import com.tom.rv2ide.models.Location;
import com.tom.rv2ide.models.Position;
import com.tom.rv2ide.models.Range;
import com.tom.rv2ide.preferences.internal.JavaPreferences;
import com.tom.rv2ide.projects.FileManager;
import com.tom.rv2ide.projects.ModuleProject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdkx.lang.model.element.Element;
import jdkx.lang.model.element.ElementKind;
import jdkx.lang.model.element.ExecutableElement;
import jdkx.lang.model.element.TypeElement;

/** Maps javac elements from Kotlin ABI stubs back to real Kotlin source declarations. */
public final class KotlinJvmSourceNavigator {

  private static final Pattern TYPE_ALIAS_PATTERN =
      Pattern.compile("(?m)^\\s*typealias\\s+([A-Za-z_][\\w]*)\\s*=\\s*([^\\r\\n]+?)\\s*$");
  private static final Pattern GENERIC_TYPE_ALIAS_PATTERN =
      Pattern.compile(
          "(?m)^\\s*typealias\\s+([A-Za-z_][\\w]*)\\s*<([^<>]+)>\\s*=\\s*([^\\r\\n]+?)\\s*$");
  private static final Pattern TYPE_NAME_PATTERN =
      Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*");
  private static final ThreadLocal<Map<String, String>> TYPE_ALIASES = new ThreadLocal<>();
  private static final ThreadLocal<Map<String, GenericTypeAlias>> GENERIC_TYPE_ALIASES =
      new ThreadLocal<>();

  private KotlinJvmSourceNavigator() {}

  public static Location find(ModuleProject module, Element element) {
    if (!JavaPreferences.INSTANCE.isJavaKotlinRecognitionEnabled()) {
      return null;
    }
    TypeElement owner = ownerType(element);
    if (module == null || owner == null) {
      return null;
    }
    final TypeElement declaredOwner = owner;
    final TypeElement topLevelOwner = topLevelOwner(owner);
    final String qualifiedName = topLevelOwner.getQualifiedName().toString();
    final List<KotlinTypeDeclaration> multifileDeclarations =
        KotlinJvmTypeIndex.findMultifileDeclarations(module, qualifiedName);
    if (!(element instanceof TypeElement) && !multifileDeclarations.isEmpty()) {
      for (KotlinTypeDeclaration multifileDeclaration : multifileDeclarations) {
        final String multifileSource =
            FileManager.INSTANCE.getDocumentContents(multifileDeclaration.file).toString();
        final Map<String, String> previousAliases = TYPE_ALIASES.get();
        final Map<String, GenericTypeAlias> previousGenericAliases = GENERIC_TYPE_ALIASES.get();
        TYPE_ALIASES.set(visibleTypeAliases(module, multifileDeclaration.file, multifileSource));
        GENERIC_TYPE_ALIASES.set(visibleGenericTypeAliases(
        module, multifileDeclaration.file, multifileSource));
        try {
          final SourceRange multifileRange = findFacadeMember(multifileSource, element);
          if (multifileRange != null) {
            return location(
                multifileDeclaration.file, multifileSource, multifileRange.offset, multifileRange.length);
          }
        } finally {
          if (previousAliases == null) {
            TYPE_ALIASES.remove();
          } else {
            TYPE_ALIASES.set(previousAliases);
          }
          if (previousGenericAliases == null) {
            GENERIC_TYPE_ALIASES.remove();
          } else {
            GENERIC_TYPE_ALIASES.set(previousGenericAliases);
          }
        }
      }
    }
    final KotlinTypeDeclaration declaration = multifileDeclarations.isEmpty()
        ? KotlinJvmTypeIndex.findDeclaration(module, qualifiedName)
        : multifileDeclarations.get(0);
    if (declaration == null) {
      return null;
    }
    final String source = FileManager.INSTANCE.getDocumentContents(declaration.file).toString();
    final Map<String, String> previousAliases = TYPE_ALIASES.get();
    final Map<String, GenericTypeAlias> previousGenericAliases = GENERIC_TYPE_ALIASES.get();
    TYPE_ALIASES.set(visibleTypeAliases(module, declaration.file, source));
    GENERIC_TYPE_ALIASES.set(visibleGenericTypeAliases(module, declaration.file, source));
    try {
      final KotlinJvmSyntaxParser.TypeSyntax topLevelType =
          KotlinJvmSyntaxParser.findTopLevelType(source, topLevelOwner.getSimpleName().toString());
      final boolean companionOwner = isCompanionOwner(declaredOwner, topLevelType, topLevelOwner);
      if (companionOwner) {
        owner = (TypeElement) declaredOwner.getEnclosingElement();
      }
      final KotlinJvmSyntaxParser.TypeSyntax type =
          nestedType(topLevelType, owner, topLevelOwner);
      if (element instanceof TypeElement && type != null && type.nameOffset >= 0) {
        return location(declaration.file, source, type.nameOffset, type.nameLength);
      }
      if (element instanceof TypeElement) {
        return location(declaration.file, source, declaration.offset, declaration.length);
      }
      final SourceRange range = type == null
          ? findFacadeMember(source, element)
          : companionOwner
              ? findMember(type.companionMembers, element, false)
              : findTypeMember(type, declaration, element);
      if (range != null) {
        return location(declaration.file, source, range.offset, range.length);
      }
      return type != null && type.nameOffset >= 0
          ? location(declaration.file, source, type.nameOffset, type.nameLength)
          : location(declaration.file, source, declaration.offset, declaration.length);
    } finally {
      if (previousAliases == null) {
        TYPE_ALIASES.remove();
      } else {
        TYPE_ALIASES.set(previousAliases);
      }
      if (previousGenericAliases == null) {
        GENERIC_TYPE_ALIASES.remove();
      } else {
        GENERIC_TYPE_ALIASES.set(previousGenericAliases);
      }
    }
  }

  private static SourceRange findTypeMember(
      KotlinJvmSyntaxParser.TypeSyntax type,
      KotlinTypeDeclaration typeDeclaration,
      Element element) {
    if (element.getKind() == ElementKind.CONSTRUCTOR && element instanceof ExecutableElement) {
      if (KotlinAbiSyntheticMembers.isSyntheticConstructor(element)) {
        return null;
      }
      final ExecutableElement executable = (ExecutableElement) element;
      final SourceRange typeRange = type.nameOffset >= 0
          ? new SourceRange(type.nameOffset, type.nameLength)
          : new SourceRange(typeDeclaration.offset, typeDeclaration.length);
      SourceRange match = null;
      int matches = 0;
      for (KotlinJvmSyntaxParser.ConstructorSyntax constructor : type.secondaryConstructors) {
        if (constructorMatches(
            constructor.parameters, constructor.jvmOverloads, executable)) {
          match = new SourceRange(constructor.nameOffset, constructor.nameLength);
          matches++;
        }
      }
      if (type.primaryConstructorPresent
          && primaryConstructorMatches(type, executable)) {
        match = typeRange;
        matches++;
      }
      return matches == 1 ? match : typeRange;
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
      final String getter = propertyGetterName(parameter.name, parameter.type);
      final String setter = propertySetterName(parameter.name, parameter.type);
      if (javaName.equals(getter)
          || (parameter.mutableProperty && javaName.equals(setter))) {
        return new SourceRange(parameter.nameOffset, parameter.nameLength);
      }
    }
    return null;
  }
  /**
   * Resolves a type, constructor, or type member inside one Kotlin source file.
   *
   * <p>This package-private entry point exercises the same nested-type, constructor, property, and
   * companion matching used by production navigation, without requiring a module source index.
   */
  static Location findTypeMemberLocation(Path file, String source, Element element) {
    if (file == null || source == null || element == null) {
      return null;
    }
    TypeElement owner = ownerType(element);
    if (owner == null) {
      return null;
    }
    final TypeElement declaredOwner = owner;
    final TypeElement topLevelOwner = topLevelOwner(owner);
    final KotlinJvmSyntaxParser.TypeSyntax topLevelType =
        KotlinJvmSyntaxParser.findTopLevelType(source, topLevelOwner.getSimpleName().toString());
    final boolean companionOwner = isCompanionOwner(declaredOwner, topLevelType, topLevelOwner);
    if (companionOwner) {
      owner = (TypeElement) declaredOwner.getEnclosingElement();
    }
    final KotlinJvmSyntaxParser.TypeSyntax type = nestedType(topLevelType, owner, topLevelOwner);
    if (type == null) {
      return null;
    }
    final Map<String, String> previousAliases = TYPE_ALIASES.get();
    final Map<String, GenericTypeAlias> previousGenericAliases = GENERIC_TYPE_ALIASES.get();
    TYPE_ALIASES.set(collectSimpleTypeAliases(source));
    GENERIC_TYPE_ALIASES.set(collectGenericTypeAliases(source));
    try {
      if (element instanceof TypeElement && type.nameOffset >= 0) {
        return location(file, source, type.nameOffset, type.nameLength);
      }
      final KotlinTypeDeclaration declaration = new KotlinTypeDeclaration(
          file, Math.max(0, type.nameOffset), Math.max(1, type.nameLength));
      final SourceRange range = companionOwner
          ? findMember(type.companionMembers, element, false)
          : findTypeMember(type, declaration, element);
      return range == null ? null : location(file, source, range.offset, range.length);
    } finally {
      if (previousAliases == null) {
        TYPE_ALIASES.remove();
      } else {
        TYPE_ALIASES.set(previousAliases);
      }
      if (previousGenericAliases == null) {
        GENERIC_TYPE_ALIASES.remove();
      } else {
        GENERIC_TYPE_ALIASES.set(previousGenericAliases);
      }
    }
  }

  /**
   * Resolves one Java facade member across Kotlin multifile facade parts.
   *
   * <p>Each source part gets an isolated same-file alias context, matching production behavior. A
   * source part only contributes a result when its own declarations identify one unique signature.
   */
  static Location findMultifileFacadeMemberLocation(
      List<Path> files, List<String> sources, Element element) {
    if (files == null || sources == null || element == null || files.size() != sources.size()) {
      return null;
    }
    for (int index = 0; index < files.size(); index++) {
      final Location location = findFacadeMemberLocation(files.get(index), sources.get(index), element);
      if (location != null) {
        return location;
      }
    }
    return null;
  }

  /**
   * Resolves a facade member inside one Kotlin source file.
   *
   * <p>This package-private entry point keeps signature matching independently testable without a
   * {@link ModuleProject}. Production navigation still uses {@link #find(ModuleProject, Element)}
   * so cross-file visibility continues to come from {@link KotlinJvmTypeIndex}.
   */
  static Location findFacadeMemberLocation(Path file, String source, Element element) {
    return findFacadeMemberLocation(
        file, source, element, Collections.emptyMap(), Collections.emptyMap());
  }

  /** Resolves a facade member with aliases supplied by the cross-file visibility index. */
  static Location findFacadeMemberLocation(
      Path file,
      String source,
      Element element,
      Map<String, String> visibleDirectAliases,
      Map<String, KotlinJvmTypeIndex.GenericTypeAlias> visibleGenericAliases) {
    if (file == null || source == null || element == null) {
      return null;
    }
    final Map<String, String> directAliases = collectSimpleTypeAliases(source);
    if (visibleDirectAliases != null) {
      for (Map.Entry<String, String> entry : visibleDirectAliases.entrySet()) {
        directAliases.putIfAbsent(entry.getKey(), entry.getValue());
      }
    }
    final Map<String, GenericTypeAlias> genericAliases = collectGenericTypeAliases(source);
    if (visibleGenericAliases != null) {
      for (Map.Entry<String, KotlinJvmTypeIndex.GenericTypeAlias> entry
          : visibleGenericAliases.entrySet()) {
        final KotlinJvmTypeIndex.GenericTypeAlias alias = entry.getValue();
        genericAliases.putIfAbsent(entry.getKey(), new GenericTypeAlias(
            alias.parameters,
            alias.targetRawType + "<" + String.join(", ", alias.targetArguments) + ">"));
      }
    }
    final Map<String, String> previousAliases = TYPE_ALIASES.get();
    final Map<String, GenericTypeAlias> previousGenericAliases = GENERIC_TYPE_ALIASES.get();
    TYPE_ALIASES.set(directAliases);
    GENERIC_TYPE_ALIASES.set(genericAliases);
    try {
      final SourceRange range = findFacadeMember(source, element);
      return range == null ? null : location(file, source, range.offset, range.length);
    } finally {
      if (previousAliases == null) {
        TYPE_ALIASES.remove();
      } else {
        TYPE_ALIASES.set(previousAliases);
      }
      if (previousGenericAliases == null) {
        GENERIC_TYPE_ALIASES.remove();
      } else {
        GENERIC_TYPE_ALIASES.set(previousGenericAliases);
      }
    }
  }

  private static SourceRange findFacadeMember(String source, Element element) {
    final List<KotlinJvmSyntaxParser.MemberSyntax> members =
        KotlinJvmSyntaxParser.findTopLevelMembers(source);
    return members == null ? null : findMember(members, element, false);
  }


  private static SourceRange findMember(
      List<KotlinJvmSyntaxParser.MemberSyntax> members, Element element, boolean requireJvmStatic) {
    final String javaName = element.getSimpleName().toString();
    final ExecutableElement executable = element instanceof ExecutableElement
        ? (ExecutableElement) element
        : null;
    SourceRange match = null;
    int matches = 0;
    for (KotlinJvmSyntaxParser.MemberSyntax member : members) {
      if (member.name == null || member.nameOffset < 0 || member.privateMember || member.jvmSynthetic) {
        continue;
      }
      if (requireJvmStatic && !member.jvmStatic && !member.jvmField) {
        continue;
      }
      final String jvmMemberName = member.jvmName == null ? member.name : member.jvmName;
      final boolean nameMatches = javaName.equals(jvmMemberName);
      final boolean matchesElement = member.function()
          ? nameMatches && functionSignatureMatches(member, executable)
          : propertyJavaNameMatches(member, javaName, element);
      if (matchesElement) {
        match = new SourceRange(member.nameOffset, member.nameLength);
        matches++;
      }
    }
    return matches == 1 ? match : null;
  }

  private static boolean functionSignatureMatches(
      KotlinJvmSyntaxParser.MemberSyntax member, ExecutableElement executable) {
    if (executable == null) {
      return false;
    }
    final int receiverCount = member.receiverType == null ? 0 : 1;
    final int parameterCount = executable.getParameters().size();
    final int kotlinParameterCount = parameterCount - receiverCount;
    final int fullCount = member.parameterList.size();
    if (kotlinParameterCount < 0
        || kotlinParameterCount != fullCount
            && (!member.jvmOverloads
                || kotlinParameterCount < trailingDefaultStart(member.parameterList)
                || kotlinParameterCount >= fullCount)) {
      return false;
    }
    if (receiverCount == 1
        && !functionParameterTypeCompatible(
            member.receiverType, false, executable.getParameters().get(0).asType().toString())) {
      return false;
    }
    for (int index = 0; index < kotlinParameterCount; index++) {
      final KotlinJvmSyntaxParser.ParameterSyntax parameter = member.parameterList.get(index);
      final boolean vararg = parameter.vararg && index == member.parameterList.size() - 1;
      final String javacType = executable.getParameters().get(index + receiverCount).asType().toString();
      if (!functionParameterTypeCompatible(parameter.type, vararg, javacType)) {
        return false;
      }
    }
    return true;
  }

  private static boolean propertyJavaNameMatches(
      KotlinJvmSyntaxParser.MemberSyntax member, String javaName, Element element) {
    if (element.getKind() == ElementKind.FIELD && member.receiverType == null
        && javaName.equals(member.name)) {
      return true;
    }
    if (element.getKind() != ElementKind.METHOD || member.name.isEmpty()
        || !(element instanceof ExecutableElement)) {
      return false;
    }
    final ExecutableElement executable = (ExecutableElement) element;
    final String getter = member.getterJvmName == null
        ? propertyGetterName(member.name, member.declaredType)
        : member.getterJvmName;
    final String setter = member.setterJvmName == null
        ? propertySetterName(member.name, member.declaredType)
        : member.setterJvmName;
    final boolean getterMatches = !member.getterJvmSynthetic && javaName.equals(getter)
        && propertyAccessorParametersMatch(member, executable, false);
    final boolean setterMatches = member.mutableProperty && !member.setterJvmSynthetic
        && javaName.equals(setter) && propertyAccessorParametersMatch(member, executable, true);
    return getterMatches || setterMatches;
  }

  private static boolean propertyAccessorParametersMatch(
      KotlinJvmSyntaxParser.MemberSyntax member, ExecutableElement executable, boolean setter) {
    final int receiverCount = member.receiverType == null ? 0 : 1;
    final int expectedCount = receiverCount + (setter ? 1 : 0);
    if (executable.getParameters().size() != expectedCount) return false;
    if (receiverCount == 1 && !parameterTypeMatches(
        member.receiverType, false, executable.getParameters().get(0).asType().toString())) {
      return false;
    }
    return !setter || parameterTypeMatches(member.declaredType, false,
        executable.getParameters().get(receiverCount).asType().toString());
  }

  private static String propertyGetterName(String name, String kotlinType) {
    return isBooleanIsProperty(name, kotlinType)
        ? name
        : "get" + propertyAccessorSuffix(name);
  }

  private static String propertySetterName(String name, String kotlinType) {
    return isBooleanIsProperty(name, kotlinType)
        ? "set" + name.substring(2)
        : "set" + propertyAccessorSuffix(name);
  }

  private static boolean isBooleanIsProperty(String name, String kotlinType) {
    if (name == null || name.length() < 3 || name.charAt(0) != 'i' || name.charAt(1) != 's'
        || !Character.isUpperCase(name.charAt(2)) || kotlinType == null) {
      return false;
    }
    final String type = kotlinType.trim();
    return "Boolean".equals(type) || "kotlin.Boolean".equals(type);
  }

  private static String propertyAccessorSuffix(String name) {
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }

  private static boolean primaryConstructorMatches(
      KotlinJvmSyntaxParser.TypeSyntax type, ExecutableElement executable) {
    final int parameterCount = executable.getParameters().size();
    final int fullCount = type.constructorParameters.size();
    if (parameterCount != fullCount
        && (!type.constructorJvmOverloads
            || parameterCount < trailingDefaultStart(type.constructorParameters)
            || parameterCount >= fullCount)) {
      return false;
    }
    for (int index = 0; index < parameterCount; index++) {
      if (!parameterTypeMatches(
          type.constructorParameters.get(index).type,
          false,
          executable.getParameters().get(index).asType().toString())) {
        return false;
      }
    }
    return true;
  }

  private static boolean constructorMatches(
      List<KotlinJvmSyntaxParser.ParameterSyntax> parameters,
      boolean jvmOverloads,
      ExecutableElement executable) {
    final int parameterCount = executable.getParameters().size();
    if (parameterCount != parameters.size()
        && (!jvmOverloads
            || parameterCount < trailingDefaultStart(parameters)
            || parameterCount >= parameters.size())) {
      return false;
    }
    for (int index = 0; index < parameterCount; index++) {
      if (!parameterTypeMatches(
          parameters.get(index).type,
          parameters.get(index).vararg && index == parameters.size() - 1,
          executable.getParameters().get(index).asType().toString())) {
        return false;
      }
    }
    return true;
  }

  private static int trailingDefaultStart(
      List<? extends Object> parameters) {
    int index = parameters.size();
    while (index > 0) {
      final Object parameter = parameters.get(index - 1);
      final boolean defaultValue = parameter instanceof KotlinJvmSyntaxParser.ParameterSyntax
          ? ((KotlinJvmSyntaxParser.ParameterSyntax) parameter).defaultValue
          : ((KotlinJvmSyntaxParser.ConstructorParameterSyntax) parameter).defaultValue;
      if (!defaultValue) break;
      index--;
    }
    return index;
  }

  private static boolean functionParameterTypeCompatible(
      String kotlinType, boolean vararg, String javaType) {
    // Unknown complex Kotlin types cannot safely disprove a candidate. They remain compatible here;
    // findMember still refuses navigation when more than one source declaration survives.
    return navigationJavaType(kotlinType) == null
        || parameterTypeMatches(kotlinType, vararg, javaType);
  }

  private static boolean parameterTypeMatches(
      String kotlinType, boolean vararg, String javaType) {
    final String normalized = navigationJavaType(kotlinType);
    if (normalized == null) {
      return false;
    }
    final String expected = vararg ? normalized + "[]" : normalized;
    if (expected.equals(javaType)
        || (expected.indexOf('.') < 0 && javaType.endsWith("." + expected))) {
      return true;
    }
    // javac/rendering variants may use `String` where the navigator normalized an alias to
    // `java.lang.String` (or vice versa). Compare simple names as a final exact JVM-type check.
    final int expectedSeparator = expected.lastIndexOf('.');
    final int actualSeparator = javaType.lastIndexOf('.');
    final String expectedSimple = expectedSeparator < 0 ? expected : expected.substring(expectedSeparator + 1);
    final String actualSimple = actualSeparator < 0 ? javaType : javaType.substring(actualSeparator + 1);
    return expectedSimple.equals(actualSimple);
  }

  private static String navigationJavaType(String kotlinType) {
    if (kotlinType == null) return null;
    String type = kotlinType.trim();
    final boolean nullable = type.endsWith("?");
    if (nullable) type = type.substring(0, type.length() - 1).trim();
    final Map<String, String> aliases = TYPE_ALIASES.get();
    if (aliases != null) {
      final String expandedAlias = aliases.get(type);
      if (expandedAlias != null) {
        type = expandedAlias;
      }
    }
    final KotlinJvmTypeProjection.TypeApplication application =
        KotlinJvmTypeProjection.parseTypeApplication(type);
    if (application != null) {
      final String expandedAlias = expandGenericTypeAlias(application);
      if (expandedAlias != null) {
        return navigationJavaType(expandedAlias);
      }
      if ("Array".equals(application.rawType) || "kotlin.Array".equals(application.rawType)) {
        if (application.arguments.size() != 1) return null;
        String element = application.arguments.get(0).trim();
        if ("*".equals(element)) return null;
        if (element.startsWith("out ")) element = element.substring(4).trim();
        else if (element.startsWith("in ")) element = element.substring(3).trim();
        final String javaElement = navigationJavaType(element);
        return javaElement == null ? null : boxedNavigationType(javaElement) + "[]";
      }
      final String rawType = KotlinJvmTypeProjection.javaCollectionType(application.rawType);
      if (rawType == null || application.arguments.isEmpty()) {
        return null;
      }
      final List<String> arguments = new ArrayList<>();
      for (String argument : application.arguments) {
        final String javaArgument = navigationJavaType(argument);
        if (javaArgument == null) {
          return null;
        }
        arguments.add(boxedNavigationType(javaArgument));
      }
      return rawType + "<" + String.join(",", arguments) + ">";
    }
    switch (type) {
      case "Byte": case "kotlin.Byte": return nullable ? "java.lang.Byte" : "byte";
      case "Short": case "kotlin.Short": return nullable ? "java.lang.Short" : "short";
      case "Int": case "kotlin.Int": return nullable ? "java.lang.Integer" : "int";
      case "Long": case "kotlin.Long": return nullable ? "java.lang.Long" : "long";
      case "Float": case "kotlin.Float": return nullable ? "java.lang.Float" : "float";
      case "Double": case "kotlin.Double": return nullable ? "java.lang.Double" : "double";
      case "Boolean": case "kotlin.Boolean": return nullable ? "java.lang.Boolean" : "boolean";
      case "Char": case "kotlin.Char": return nullable ? "java.lang.Character" : "char";
      case "String": case "kotlin.String": return "java.lang.String";
      case "IntArray": case "kotlin.IntArray": return "int[]";
      case "LongArray": case "kotlin.LongArray": return "long[]";
      case "BooleanArray": case "kotlin.BooleanArray": return "boolean[]";
      case "CharArray": case "kotlin.CharArray": return "char[]";
      case "ByteArray": case "kotlin.ByteArray": return "byte[]";
      case "ShortArray": case "kotlin.ShortArray": return "short[]";
      case "FloatArray": case "kotlin.FloatArray": return "float[]";
      case "DoubleArray": case "kotlin.DoubleArray": return "double[]";
      default:
        if (type.startsWith("Array<") && type.endsWith(">")) {
          final String element = navigationJavaType(
              type.substring("Array<".length(), type.length() - 1).trim());
          return element == null ? null : element + "[]";
        }
        if (type.matches("[A-Z][A-Za-z0-9_$]*")) {
          return type;
        }
        return null;
    }
  }

  private static String expandGenericTypeAlias(
      KotlinJvmTypeProjection.TypeApplication application) {
    final Map<String, GenericTypeAlias> aliases = GENERIC_TYPE_ALIASES.get();
    final GenericTypeAlias alias = aliases == null ? null : aliases.get(application.rawType);
    return alias == null ? null : KotlinJvmTypeProjection.expandGenericAlias(
        application, alias.parameters, alias.target, TYPE_NAME_PATTERN);
  }

  // Collection-name normalization is shared with the ABI generator.

  private static String boxedNavigationType(String type) {
    switch (type) {
      case "byte": return "java.lang.Byte";
      case "short": return "java.lang.Short";
      case "int": return "java.lang.Integer";
      case "long": return "java.lang.Long";
      case "float": return "java.lang.Float";
      case "double": return "java.lang.Double";
      case "boolean": return "java.lang.Boolean";
      case "char": return "java.lang.Character";
      default: return type;
    }
  }

  // Type-application parsing is shared with the ABI generator.

  // Generic argument splitting is shared with generation and indexing.

  private static Map<String, String> visibleTypeAliases(
      ModuleProject module, Path consumerFile, String source) {
    final Map<String, String> aliases = new LinkedHashMap<>(collectSimpleTypeAliases(source));
    for (Map.Entry<String, String> alias :
        KotlinJvmTypeIndex.visibleDirectTypeAliases(module, consumerFile).entrySet()) {
      aliases.putIfAbsent(alias.getKey(), alias.getValue());
    }
    return aliases;
  }

  private static Map<String, GenericTypeAlias> visibleGenericTypeAliases(
      ModuleProject module, Path consumerFile, String source) {
    final Map<String, GenericTypeAlias> aliases = collectGenericTypeAliases(source);
    for (Map.Entry<String, KotlinJvmTypeIndex.GenericTypeAlias> entry
        : KotlinJvmTypeIndex.visibleGenericTypeAliases(module, consumerFile).entrySet()) {
      final KotlinJvmTypeIndex.GenericTypeAlias value = entry.getValue();
      aliases.putIfAbsent(entry.getKey(), new GenericTypeAlias(
          value.parameters,
          value.targetRawType + "<" + String.join(", ", value.targetArguments) + ">"));
    }
    return aliases;
  }

  private static Map<String, GenericTypeAlias> collectGenericTypeAliases(String source) {
    final Map<String, GenericTypeAlias> aliases = new LinkedHashMap<>();
    final Matcher matcher = GENERIC_TYPE_ALIAS_PATTERN.matcher(source);
    while (matcher.find()) {
      final List<String> parameters = new ArrayList<>();
      for (String parameter : KotlinJvmTypeProjection.splitTopLevelArguments(matcher.group(2))) {
        final String trimmed = parameter.trim();
        if (!TYPE_NAME_PATTERN.matcher(trimmed).matches()) {
          parameters.clear();
          break;
        }
        parameters.add(trimmed);
      }
      final String target = matcher.group(3).trim();
      final KotlinJvmTypeProjection.TypeApplication targetApplication =
          KotlinJvmTypeProjection.parseTypeApplication(target);
      if (parameters.isEmpty() || targetApplication == null || target.endsWith("?")
          || target.indexOf("->") >= 0 || target.indexOf('&') >= 0 || target.indexOf('|') >= 0
          || targetApplication.rawType.indexOf('.') >= 0) {
        continue;
      }
      boolean valid = true;
      for (String argument : targetApplication.arguments) {
        if (!TYPE_NAME_PATTERN.matcher(argument.trim()).matches()) {
          valid = false;
          break;
        }
      }
      if (valid) {
        aliases.putIfAbsent(matcher.group(1), new GenericTypeAlias(parameters, target));
      }
    }
    return aliases;
  }

  private static Map<String, String> collectSimpleTypeAliases(String source) {
    final Map<String, String> aliases = new LinkedHashMap<>();
    final Matcher matcher = TYPE_ALIAS_PATTERN.matcher(source);
    while (matcher.find()) {
      final String name = matcher.group(1);
      final String target = matcher.group(2).trim();
      if (target.endsWith("?") || target.indexOf("->") >= 0 || target.indexOf('&') >= 0
          || target.indexOf('|') >= 0 || target.indexOf('(') >= 0 || target.indexOf(')') >= 0) {
        continue;
      }
      aliases.put(name, target);
    }
    aliases.values().removeIf(aliases::containsKey);
    return aliases;
  }

  private static final class GenericTypeAlias {
    final List<String> parameters;
    final String target;

    GenericTypeAlias(List<String> parameters, String target) {
      this.parameters = new ArrayList<>(parameters);
      this.target = target;
    }
  }

  // TypeApplication is defined by KotlinJvmTypeProjection.

  private static boolean isCompanionOwner(
      TypeElement owner,
      KotlinJvmSyntaxParser.TypeSyntax topLevelType,
      TypeElement topLevelOwner) {
    if (owner == null || topLevelType == null || owner.equals(topLevelOwner)
        || !(owner.getEnclosingElement() instanceof TypeElement)) {
      return false;
    }
    final TypeElement enclosing = (TypeElement) owner.getEnclosingElement();
    final KotlinJvmSyntaxParser.TypeSyntax enclosingSyntax =
        nestedType(topLevelType, enclosing, topLevelOwner);
    return enclosingSyntax != null && enclosingSyntax.companionBody != null
        && owner.getSimpleName().contentEquals(enclosingSyntax.companionName);
  }

  private static TypeElement topLevelOwner(TypeElement owner) {
    TypeElement current = owner;
    while (current.getEnclosingElement() instanceof TypeElement) {
      current = (TypeElement) current.getEnclosingElement();
    }
    return current;
  }

  private static KotlinJvmSyntaxParser.TypeSyntax nestedType(
      KotlinJvmSyntaxParser.TypeSyntax topLevel,
      TypeElement owner,
      TypeElement topLevelOwner) {
    if (topLevel == null || owner.equals(topLevelOwner)) {
      return topLevel;
    }
    final java.util.ArrayList<String> names = new java.util.ArrayList<>();
    Element current = owner;
    while (current instanceof TypeElement && !current.equals(topLevelOwner)) {
      names.add(0, current.getSimpleName().toString());
      current = current.getEnclosingElement();
    }
    KotlinJvmSyntaxParser.TypeSyntax result = topLevel;
    for (String name : names) {
      KotlinJvmSyntaxParser.TypeSyntax match = null;
      for (KotlinJvmSyntaxParser.TypeSyntax nested : result.nestedTypes) {
        if (name.equals(nested.name)) {
          match = nested;
          break;
        }
      }
      if (match == null) {
        return null;
      }
      result = match;
    }
    return result;
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