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
package com.tom.rv2ide.lsp.java.rewrite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.tom.rv2ide.lsp.java.compiler.CompileTask;
import com.tom.rv2ide.lsp.java.compiler.CompilerProvider;
import com.tom.rv2ide.lsp.java.compiler.SynchronizedTask;
import com.tom.rv2ide.lsp.java.utils.EditHelper;
import com.tom.rv2ide.lsp.java.utils.FindHelper;
import com.tom.rv2ide.lsp.java.utils.MethodStubGenerator;

import com.tom.rv2ide.lsp.java.visitors.FindAnonymousTypeDeclaration;
import com.tom.rv2ide.lsp.java.visitors.FindTypeDeclarationAt;
import com.tom.rv2ide.lsp.models.CodeActionItem;
import com.tom.rv2ide.lsp.models.Command;
import com.tom.rv2ide.lsp.models.TextEdit;
import com.tom.rv2ide.models.Position;
import com.tom.rv2ide.models.Range;
import com.tom.rv2ide.preferences.internal.EditorPreferences;
import com.tom.rv2ide.preferences.utils.EditorUtilKt;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import jdkx.lang.model.element.Element;
import jdkx.lang.model.element.ElementKind;
import jdkx.lang.model.element.ExecutableElement;
import jdkx.lang.model.element.Modifier;
import jdkx.lang.model.element.TypeElement;
import jdkx.lang.model.type.ExecutableType;
import jdkx.lang.model.util.Elements;
import openjdk.source.tree.ClassTree;
import openjdk.source.tree.CompilationUnitTree;
import openjdk.source.tree.ImportTree;
import openjdk.source.tree.LineMap;
import openjdk.source.tree.Tree;
import openjdk.source.util.SourcePositions;
import openjdk.source.util.Trees;
import openjdk.tools.javac.util.JCDiagnostic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImplementAbstractMethods extends Rewrite {

  private static final Logger LOG = LoggerFactory.getLogger(ImplementAbstractMethods.class);
  private final String className;
  private final String classFile;
  private final String superTypeName;
  private final long position;

  public ImplementAbstractMethods(@NonNull JCDiagnostic diagnostic) {
    Object[] args = diagnostic.getArgs();
    String targetName = args[0].toString();

    if (!isAnonymousTarget(targetName)) {
      this.className = targetName;
      this.classFile = targetName;
      this.superTypeName = targetName;
      this.position = 0;
    } else {
      String ownerName = extractAnonymousOwner(targetName);
      this.classFile = ownerName;
      this.className = targetName;
      this.superTypeName = args[2].toString();
      this.position = diagnostic.getStartPosition();
    }
  }
 
  @NonNull
  @Override
  public Map<Path, TextEdit[]> rewrite(@NonNull CompilerProvider compiler) {
    final Path file = compiler.findTypeDeclaration(this.classFile);
    if (file == CompilerProvider.NOT_FOUND) {
      LOG.warn("Unable to find source file for class: {} classFile={}", this.className,
          this.classFile);
      return CANCELLED;
    }

    final SynchronizedTask synchronizedTask = compiler.compile(file);
    return synchronizedTask.get(
        task -> {
          final CompilationUnitTree fileRoot = task.root(file);
          StringBuilder insertText = new StringBuilder();
          Elements elements = task.task.getElements();
          Trees trees = Trees.instance(task.task);
          TypeElement superType = elements.getTypeElement(this.superTypeName);

          ClassTree thisTree = getClassTree(task, file);
          TypeElement thisClass = null;
          if (thisTree != null) {
            thisClass = (TypeElement) trees.getElement(trees.getPath(fileRoot, thisTree));
          }
          if (thisTree == null) {
            thisClass = elements.getTypeElement(this.className);
            if (thisClass != null) {
              thisTree = trees.getTree(thisClass);
            }
          }

          if (thisTree == null || thisClass == null || superType == null) {
            LOG.warn(
                "ImplementAbstractMethods could not resolve target class. className={} classFile={} superTypeName={} position={}",
                this.className,
                this.classFile,
                this.superTypeName,
                this.position);
            return CANCELLED;
          }
          final Set<String> imports = new TreeSet<>();
          final Set<String> addedMethods = new HashSet<>();
          Position insert = EditHelper.insertAtEndOfClass(task.task, fileRoot, thisTree);
          final CharSequence source;
          try {
            source = fileRoot.getSourceFile().getCharContent(true);
          } catch (IOException e) {
            LOG.warn("ImplementAbstractMethods could not read source content for className={} classFile={}", this.className, this.classFile, e);
            return CANCELLED;
          }
          final SourcePositions sourcePositions = Trees.instance(task.task).getSourcePositions();
          final long treeStartOffset = sourcePositions.getStartPosition(fileRoot, thisTree);
          final long treeEndOffset = sourcePositions.getEndPosition(fileRoot, thisTree);
          int openBraceOffset = -1;
          int closeBraceOffset = -1;
          for (int i = (int) Math.max(0, treeStartOffset); i < source.length() && i < treeEndOffset; i++) {
            if (source.charAt(i) == '{') {
              openBraceOffset = i;
              break;
            }
          }
          for (int i = (int) Math.min(treeEndOffset - 1, source.length() - 1); i >= 0 && i >= treeStartOffset; i--) {
            if (source.charAt(i) == '}') {
              closeBraceOffset = i;
              break;
            }
          }
          int braceIndent = EditHelper.lineIndent(task.task, fileRoot, thisTree);
          int indent = braceIndent + EditorPreferences.INSTANCE.getTabSize();
          for (Element member : elements.getAllMembers(thisClass)) {
            if (member.getKind() != ElementKind.METHOD || !member.getModifiers().contains(Modifier.ABSTRACT)) {
              continue;
            }
            ExecutableElement method = (ExecutableElement) member;
            if (method.getSimpleName().contentEquals("<init>")) {
              continue;
            }
            if (method.getEnclosingElement() instanceof TypeElement) {
              TypeElement owner = (TypeElement) method.getEnclosingElement();
              if (owner.getQualifiedName().contentEquals("java.lang.Object")) {
                continue;
              }
            }
            String signatureKey =
                method.getSimpleName()
                    + "#"
                    + String.join(",", FindHelper.erasedParameterTypes(task, method));
            if (!addedMethods.add(signatureKey)) {
              continue;
            }
            final ExecutableType executableType;
            if (thisClass.asType() instanceof jdkx.lang.model.type.DeclaredType) {
              executableType =
                  (ExecutableType)
                      task.task.getTypes().asMemberOf(
                          (jdkx.lang.model.type.DeclaredType) thisClass.asType(), method);
            } else {
              final openjdk.source.util.TreePath methodPath = trees.getPath(method);
              if (methodPath == null) {
                LOG.warn(
                    "ImplementAbstractMethods could not resolve method path. className={} method={}",
                    this.className,
                    method);
                continue;
              }
              executableType = (ExecutableType) trees.getTypeMirror(methodPath);
            }
            final MethodStubGenerator.GeneratedMethod generated =
                MethodStubGenerator.generate(
                    method,
                    executableType,
                    null,
                    MethodStubGenerator.BodyStrategy.IMPLEMENT_ABSTRACT);
            imports.addAll(generated.getImports());
            String memberIndent = EditorUtilKt.indentationString(indent);
            String text = generated.getRenderedText();
            if (insertText.length() == 0) {
              insertText.append("\n\n");
            } else {
              insertText.append("\n");
            }
            String[] lines = text.split("\\r?\\n", -1);
            for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
              if (lineIndex > 0) {
                insertText.append("\n");
              }
              insertText.append(memberIndent);
              insertText.append(lines[lineIndex]);
            }
            insertText.append("\n");
            insertText.append(EditorUtilKt.indentationString(braceIndent));
          }

          if (insertText.length() == 0) {
            LOG.warn(
                "ImplementAbstractMethods found no abstract methods to generate. className={} superTypeName={} position={}",
                this.className,
                this.superTypeName,
                this.position);
            return CANCELLED;
          }

          final List<TextEdit> edits = new ArrayList<>();
          if (openBraceOffset >= 0 && closeBraceOffset > openBraceOffset) {
            // For anonymous classes, replacing only the body keeps the closing brace and semicolon
            // structurally stable for both compact (`new X() {};`) and multi-line forms.
            final LineMap lineMap = fileRoot.getLineMap();
            final Position replaceStart = new Position(
                (int) lineMap.getLineNumber(openBraceOffset + 1) - 1,
                (int) lineMap.getColumnNumber(openBraceOffset + 1) - 1,
                openBraceOffset + 1);
            final Position replaceEnd = new Position(
                (int) lineMap.getLineNumber(closeBraceOffset) - 1,
                (int) lineMap.getColumnNumber(closeBraceOffset) - 1,
                closeBraceOffset);
            edits.add(new TextEdit(new Range(replaceStart, replaceEnd), insertText.toString()));
          } else {
            edits.add(new TextEdit(new Range(insert, insert), insertText.toString()));
          }
          addImports(compiler, task, file, imports, edits);

          return Collections.singletonMap(file, edits.toArray(new TextEdit[0]));
        });
  }

  private void addImports(
      CompilerProvider compiler,
      CompileTask task,
      Path file,
      Set<String> imports,
      List<TextEdit> edits) {
    imports =
        imports.stream()
            .filter(name -> !name.startsWith("java.lang."))
            .filter(name -> name.contains("."))
            .collect(Collectors.toSet());
    for (String name : imports) {
      final List<TextEdit> importEdits =
          EditHelper.addImportIfNeeded(compiler, file, getFileImports(task, file), name);
      if (importEdits != null && !importEdits.isEmpty()) {
        edits.addAll(importEdits);
      }
    }
  }

  private Set<String> getFileImports(@NonNull CompileTask task, Path file) {
    return task.root(file).getImports().stream()
        .map(ImportTree::getQualifiedIdentifier)
        .map(Tree::toString)
        .collect(Collectors.toSet());
  }

  @Nullable
  private ClassTree getClassTree(@NonNull CompileTask task, Path file) {
    ClassTree thisTree = null;
    CompilationUnitTree root = task.root(file);
    if (root == null) {
      return null;
    }

    if (position != 0) {
      final FindTypeDeclarationAt scanner = new FindTypeDeclarationAt(task.task);
      thisTree = scanner.scan(root, position);
    }

    if (thisTree == null) {
      final FindAnonymousTypeDeclaration scanner =
          new FindAnonymousTypeDeclaration(task.task, root);
      thisTree = scanner.scan(root, position);
    }

    return thisTree;
  }

  @Override
  protected void finalizeCodeAction(@NonNull CodeActionItem action) {
    action.setCommand(new Command("Format code", Command.FORMAT_CODE));
  }
  private static boolean isAnonymousTarget(String targetName) {
    return targetName.length() > 2
        && targetName.charAt(0) == '<'
        && targetName.charAt(targetName.length() - 1) == '>';
  }

  private static String extractAnonymousOwner(String targetName) {
    String owner = targetName.substring(1, targetName.length() - 1).trim();

    // Diagnostic display names for anonymous types are localized, so prefer extracting the
    // binary name tail instead of matching a specific language prefix such as "anonymous".
    int binaryNameStart = findBinaryNameStart(owner);
    if (binaryNameStart > 0 && binaryNameStart < owner.length()) {
      owner = owner.substring(binaryNameStart);
    } else {
      int lastSpace = owner.lastIndexOf(' ');
      if (lastSpace >= 0 && lastSpace + 1 < owner.length()) {
        owner = owner.substring(lastSpace + 1);
      }
    }

    int dollar = owner.indexOf('$');
    if (dollar >= 0) {
      owner = owner.substring(0, dollar);
    }
    return owner;
  }

  private static int findBinaryNameStart(String text) {
    for (int index = 0; index < text.length(); index++) {
      char current = text.charAt(index);
      if (!Character.isJavaIdentifierStart(current)) {
        continue;
      }
      int nextDot = text.indexOf('.', index);
      if (nextDot > index) {
        return index;
      }
    }
    return -1;
  }

}
