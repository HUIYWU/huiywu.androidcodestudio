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

package com.tom.rv2ide.lsp.java.visitors;

import openjdk.source.tree.ClassTree;
import openjdk.source.tree.LambdaExpressionTree;
import openjdk.source.tree.MemberReferenceTree;
import openjdk.source.tree.NewClassTree;
import openjdk.source.util.TreeScanner;

/** Scans a method body for local classes, anonymous classes and lambdas. */
public final class FindPartialReparseRiskyConstructs extends TreeScanner<Void, Void> {

  private boolean hasLocalClass;
  private boolean hasAnonymousClass;
  private boolean hasLambda;
  private boolean hasMethodReference;

  public boolean hasRiskyConstructs() {
    return hasLocalClass || hasAnonymousClass || hasLambda || hasMethodReference;
  }

  public String firstReason() {
    if (hasLocalClass) {
      return "current method contains a local class";
    }
    if (hasAnonymousClass) {
      return "current method contains an anonymous class";
    }
    if (hasLambda) {
      return "current method contains a lambda";
    }
    if (hasMethodReference) {
      return "current method contains a method reference";
    }
    return null;
  }

  @Override
  public Void visitClass(ClassTree node, Void unused) {
    if (node != null && node.getSimpleName() != null && node.getSimpleName().length() > 0) {
      hasLocalClass = true;
    }
    return super.visitClass(node, unused);
  }

  @Override
  public Void visitNewClass(NewClassTree node, Void unused) {
    if (node != null && node.getClassBody() != null) {
      hasAnonymousClass = true;
    }
    return super.visitNewClass(node, unused);
  }

  @Override
  public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
    if (node != null) {
      hasLambda = true;
    }
    return super.visitLambdaExpression(node, unused);
  }

  @Override
  public Void visitMemberReference(MemberReferenceTree node, Void unused) {
    if (node != null) {
      hasMethodReference = true;
    }
    return super.visitMemberReference(node, unused);
  }
}
