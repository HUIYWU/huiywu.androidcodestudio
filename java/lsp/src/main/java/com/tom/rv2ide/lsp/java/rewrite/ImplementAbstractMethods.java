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
import openjdk.source.tree.Tree;
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

    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "ImplementAbstractMethods ctor code={} start={} args={}",
          diagnostic.getCode(),
          diagnostic.getStartPosition(),
          java.util.Arrays.toString(args));
    }

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

    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "ImplementAbstractMethods resolved className={} classFile={} superTypeName={} position={}",
          this.className,
          this.classFile,
          this.superTypeName,
          this.position);
    }
  }

  @NonNull
  @Override
  public Map<Path, TextEdit[]> rewrite(@NonNull CompilerProvider compiler) {
    final Path file = compiler.findTypeDeclaration(this.classFile);
    if (LOG.isDebugEnabled()) {
      LOG.debug("ImplementAbstractMethods rewrite className={} classFile={} file={}", this.className,
          this.classFile, file);
    }
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

          if (LOG.isDebugEnabled()) {
            LOG.debug(
                "ImplementAbstractMethods type lookup className={} type={} superTypeName={} superType={}",
                this.className,
                thisClass,
                this.superTypeName,
                superType);
            LOG.debug("ImplementAbstractMethods class tree position={} kind={}", this.position, thisTree.getKind());
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
          int indent = EditHelper.lineIndent(task.task, fileRoot, thisTree) + EditorPreferences.INSTANCE.getTabSize();
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
            if (LOG.isDebugEnabled()) {
              LOG.debug(
                  "ImplementAbstractMethods generated method={} rendered={} declaration={}",
                  method,
                  generated.getRenderedText(),
                  generated.getDeclaration());
            }
            imports.addAll(generated.getImports());
            String memberIndent = EditorUtilKt.indentationString(indent);
            String text = generated.getRenderedText();
            if (insertText.length() == 0) {
              insertText.append("\n\n");
            } else {
              insertText.append("\n");
            }
            insertText.append(memberIndent);
            insertText.append(text.replace("\n", "\n" + memberIndent));
            insertText.append("\n");
          }

          if (insertText.length() == 0) {
            LOG.warn(
                "ImplementAbstractMethods found no abstract methods to generate. className={} superTypeName={} position={}",
                this.className,
                this.superTypeName,
                this.position);
            return CANCELLED;
          }

          if (LOG.isDebugEnabled()) {
            long treeStart = Trees.instance(task.task).getSourcePositions().getStartPosition(fileRoot, thisTree);
            long treeEnd = Trees.instance(task.task).getSourcePositions().getEndPosition(fileRoot, thisTree);
            int classIndent = EditHelper.indent(task.task, fileRoot, thisTree);
            int lineIndent = EditHelper.lineIndent(task.task, fileRoot, thisTree);
            LOG.debug("ImplementAbstractMethods treeStart={} treeEnd={} insert position={} classIndent={} lineIndent={} indent={} final insert text={}", treeStart, treeEnd, insert, classIndent, lineIndent, indent, insertText);
          }
          final List<TextEdit> edits = new ArrayList<>();
          edits.add(new TextEdit(new Range(insert, insert), insertText.toString()));
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
        imports.stream().filter(name -> !name.startsWith("java.lang.")).collect(Collectors.toSet());
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
    return targetName.startsWith("<anonymous ") || targetName.startsWith("<匿名");
  }

  private static String extractAnonymousOwner(String targetName) {
    String owner = targetName.substring(1, targetName.length() - 1);
    if (owner.startsWith("anonymous ")) {
      owner = owner.substring("anonymous ".length());
    } else if (owner.startsWith("匿名")) {
      owner = owner.substring("匿名".length());
    }
    int dollar = owner.indexOf('$');
    if (dollar >= 0) {
      owner = owner.substring(0, dollar);
    }
    return owner;
  }
}
