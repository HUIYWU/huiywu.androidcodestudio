package com.tom.rv2ide.projects.gradleedit

import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.groovy.TSLanguageGroovy
import com.itsaky.androidide.treesitter.kotlin.TSLanguageKotlin

internal object GradleParser {
  data class Literal(val value: String, val start: Int, val end: Int)
  data class Call(val name: String, val start: Int, val end: Int, val arguments: List<Literal>?, val dynamic: Boolean)

  /** A boolean assignment declared directly inside a buildFeatures block. [enabled] is null for dynamic values. */
  data class BuildFeature(val name: String, val enabled: Boolean?, val valueStart: Int, val valueEnd: Int)

  /** A buildFeatures block: brace range plus the feature assignments declared directly in it. */
  data class BuildFeaturesBlock(val openOffset: Int, val closeOffset: Int, val features: List<BuildFeature>)

  fun describeTree(source: String, dsl: GradleDsl): String {
    val parser = TSParser.create()
    return try {
      parser.setLanguage(if (dsl == GradleDsl.KOTLIN) TSLanguageKotlin.getInstance() else TSLanguageGroovy.getInstance())
      val tree = parser.parseString(source) ?: return "<no tree>"
      try {
        buildString { describeNode(tree.rootNode, 0, this) }
      } finally {
        tree.close()
      }
    } finally {
      parser.close()
    }
  }

  fun parse(source: String, dsl: GradleDsl): List<Call> {
    val parser = TSParser.create()
    return try {
      parser.setLanguage(if (dsl == GradleDsl.KOTLIN) TSLanguageKotlin.getInstance() else TSLanguageGroovy.getInstance())
      val tree = parser.parseString(source) ?: return emptyList()
      try {
        val result = mutableListOf<Call>()
        visit(tree.rootNode, source, dsl, result)
        return result
      } finally {
        tree.close()
      }
    } finally {
      parser.close()
    }
  }

  /**
   * Collects every buildFeatures block in the script, in source order. Only direct boolean
   * assignments of the block body are reported; comments and nested statements are skipped,
   * and dynamic values are reported with a null [BuildFeature.enabled].
   */
  fun parseBuildFeatures(source: String, dsl: GradleDsl): List<BuildFeaturesBlock> {
    val parser = TSParser.create()
    return try {
      parser.setLanguage(if (dsl == GradleDsl.KOTLIN) TSLanguageKotlin.getInstance() else TSLanguageGroovy.getInstance())
      val tree = parser.parseString(source) ?: return emptyList()
      try {
        val result = mutableListOf<BuildFeaturesBlock>()
        collectBuildFeatures(tree.rootNode, source, dsl, result)
        result
      } finally {
        tree.close()
      }
    } finally {
      parser.close()
    }
  }

  private fun collectBuildFeatures(node: TSNode, source: String, dsl: GradleDsl, result: MutableList<BuildFeaturesBlock>) {
    if (!node.canAccess()) return
    if ((node.type == "call_expression" || node.type == "command_chain") && callName(node, source) == "buildFeatures") {
      buildFeaturesBlock(node, source, dsl)?.let { result += it }
    }
    for (index in 0 until node.namedChildCount) {
      val child = node.getNamedChild(index)
      if (child.canAccess()) collectBuildFeatures(child, source, dsl, result)
    }
  }

  private fun buildFeaturesBlock(node: TSNode, source: String, dsl: GradleDsl): BuildFeaturesBlock? {
    val body: TSNode
    val features: List<BuildFeature>
    if (dsl == GradleDsl.KOTLIN) {
      body = findDescendant(node) { it.type == "lambda_literal" } ?: return null
      features = kotlinBuildFeatures(body, source)
    } else {
      val closure = node.getChildByFieldName("argument")
      if (!closure.canAccess() || closure.type != "closure") return null
      body = closure
      features = groovyBuildFeatures(body, source)
    }
    val open = offset(body.startByte)
    val close = offset(body.endByte) - 1
    if (open !in source.indices || close !in source.indices || source[open] != '{' || source[close] != '}') return null
    return BuildFeaturesBlock(open, close, features)
  }

  private fun kotlinBuildFeatures(lambda: TSNode, source: String): List<BuildFeature> {
    var statements: TSNode? = null
    for (index in 0 until lambda.namedChildCount) {
      val child = lambda.getNamedChild(index)
      if (child.canAccess() && child.type == "statements") {
        statements = child
        break
      }
    }
    val body = statements ?: return emptyList()
    val features = mutableListOf<BuildFeature>()
    for (index in 0 until body.namedChildCount) {
      val assignment = body.getNamedChild(index)
      if (!assignment.canAccess() || assignment.type != "assignment" || assignment.namedChildCount < 2) continue
      val targetNode = assignment.getNamedChild(0)
      val target = simpleTargetName(targetNode, source) ?: continue
      val value = assignment.getNamedChild(1)
      if (!value.canAccess() || !hasPlainAssignmentOperator(source, targetNode, value)) continue
      features += buildFeature(target, value, source)
    }
    return features
  }

  private fun groovyBuildFeatures(closure: TSNode, source: String): List<BuildFeature> {
    val features = mutableListOf<BuildFeature>()
    for (index in 0 until closure.namedChildCount) {
      val child = closure.getNamedChild(index)
      if (!child.canAccess()) continue
      if (child.type == "command_chain") {
        val receiver = child.getChildByFieldName("receiver")
        val argument = child.getChildByFieldName("argument")
        if (receiver.canAccess() && receiver.type == "identifier" && argument.canAccess()) {
          features += buildFeature(text(receiver, source), argument, source)
        }
        continue
      }
      if (child.type != "expression_statement" || child.namedChildCount != 1) continue
      val expression = child.getNamedChild(0)
      if (!expression.canAccess()) continue
      if (expression.type == "assignment_expression") {
        val left = expression.getChildByFieldName("left")
        val right = expression.getChildByFieldName("right")
        if (left.canAccess() && left.type == "identifier" && right.canAccess() &&
            hasPlainAssignmentOperator(source, left, right)) {
          features += buildFeature(text(left, source), right, source)
        }
      } else if (expression.type == "method_invocation") {
        val function = expression.getChildByFieldName("function")
        val arguments = expression.getChildByFieldName("arguments")
        if (function.canAccess() && function.type == "identifier" && arguments.canAccess() && arguments.namedChildCount == 1) {
          val value = arguments.getNamedChild(0)
          if (value.canAccess()) features += buildFeature(text(function, source), value, source)
        }
      }
    }
    return features
  }

  private fun simpleTargetName(node: TSNode, source: String): String? {
    if (!node.canAccess()) return null
    if (node.type == "simple_identifier" || node.type == "identifier") return text(node, source)
    if (node.namedChildCount != 1) return null
    val child = node.getNamedChild(0)
    return if (child.canAccess() && (child.type == "simple_identifier" || child.type == "identifier")) text(child, source) else null
  }

  private fun hasPlainAssignmentOperator(source: String, left: TSNode, right: TSNode): Boolean {
    val start = offset(left.endByte)
    val end = offset(right.startByte)
    if (start > end || end > source.length) return false
    return source.substring(start, end).trim() == "="
  }

  private fun buildFeature(name: String, value: TSNode, source: String): BuildFeature {
    val enabled = if (value.type == "boolean_literal") text(value, source) == "true" else null
    return BuildFeature(name, enabled, offset(value.startByte), offset(value.endByte))
  }

  private fun visit(node: TSNode, source: String, dsl: GradleDsl, result: MutableList<Call>) {
    if (!node.canAccess()) return
    val type = node.type
    if (type == "call_expression" || type == "method_invocation" || type == "command_chain") {
      val name = callName(node, source)
      if (name != null) {
        val arguments = if (type == "command_chain") commandChainArguments(node, source) else argumentNode(node)?.let { argumentList(it, source) }
        result += Call(name, offset(node.startByte), offset(node.endByte), arguments?.first, arguments?.second == true)
      }
    }
    if (dsl == GradleDsl.GROOVY) {
      for (index in 0 until node.namedChildCount - 1) {
        val configuration = directExpressionChild(node.getNamedChild(index))
        val invocation = directExpressionChild(node.getNamedChild(index + 1))
        if (configuration != null && configuration.canAccess() && configuration.type == "identifier" &&
            invocation != null && invocation.canAccess() && invocation.type == "method_invocation") {
          val arguments = argumentNode(invocation)?.let { argumentList(it, source) }
          result += Call(
              text(configuration, source),
              offset(configuration.startByte),
              offset(invocation.endByte),
              arguments?.first,
              arguments?.second == true,
          )
        }
      }
    }
    for (index in 0 until node.namedChildCount) {
      val child = node.getNamedChild(index)
      if (child.canAccess()) visit(child, source, dsl, result)
    }
  }

  private fun callName(node: TSNode, source: String): String? {
    if (node.type == "call_expression") {
      val function = node.getChildByFieldName("function")
      if (function.canAccess() && function.type == "simple_identifier") return text(function, source)
      if (node.namedChildCount == 0) return null
      val callee = node.getNamedChild(0)
      return if (callee.canAccess() && callee.type == "simple_identifier") text(callee, source) else null
    }
    if (node.type == "method_invocation") {
      val function = node.getChildByFieldName("function")
      if (function.canAccess() && (function.type == "identifier" || function.type == "simple_identifier")) return text(function, source)
      for (index in 0 until node.namedChildCount) {
        val child = node.getNamedChild(index)
        if (child.canAccess() && (child.type == "identifier" || child.type == "simple_identifier")) return text(child, source)
      }
    }
    if (node.type == "command_chain") {
      val receiver = node.getChildByFieldName("receiver")
      if (receiver.canAccess() && receiver.type == "identifier") return text(receiver, source)
      if (node.namedChildCount > 0) {
        val first = node.getNamedChild(0)
        if (first.canAccess() && first.type == "identifier") return text(first, source)
      }
    }
    return null
  }

  private fun directExpressionChild(node: TSNode): TSNode? {
    if (!node.canAccess()) return null
    if (node.type != "expression_statement") return node
    if (node.namedChildCount != 1) return node
    val child = node.getNamedChild(0)
    return if (child.canAccess()) child else null
  }

  private fun commandChainArguments(node: TSNode, source: String): Pair<List<Literal>, Boolean> {
    val literals = mutableListOf<Literal>()
    var dynamic = false
    for (index in 0 until node.namedChildCount) {
      val argument = node.getNamedChild(index)
      if (!argument.canAccess() || argument.type == "identifier") continue
      if (argument.type != "string_literal") {
        dynamic = true
        continue
      }
      val raw = text(argument, source)
      val quote = raw.firstOrNull()
      if (raw.length < 2 || (quote != '\'' && quote != '"') || raw.last() != quote) {
        dynamic = true
        continue
      }
      literals += Literal(raw.substring(1, raw.length - 1), offset(argument.startByte), offset(argument.endByte))
    }
    return literals to dynamic
  }

  private fun argumentNode(node: TSNode): TSNode? {
    val field = node.getChildByFieldName("arguments")
    if (field.canAccess()) return field
    return findDescendant(node) { it.type == "value_arguments" || it.type == "argument_list" }
  }

  private fun argumentList(node: TSNode, source: String): Pair<List<Literal>, Boolean> {
    val literals = mutableListOf<Literal>()
    var dynamic = false
    for (index in 0 until node.namedChildCount) {
      val argument = node.getNamedChild(index)
      if (!argument.canAccess()) {
        dynamic = true
        continue
      }
      val directString = directStringArgument(argument)
      val string = directString
        ?: findDescendant(argument) { it.type == "string_literal" || it.type == "string" }
      if (string == null) {
        dynamic = true
        continue
      }
      val raw = text(string, source)
      val value = raw.substringAfter("\"").substringBeforeLast("\"")
        .takeIf { raw.startsWith("\"") } ?: raw.substringAfter("'").substringBeforeLast("'")
      literals += Literal(value, offset(string.startByte), offset(string.endByte))
      if (directString == null || value.contains('$')) dynamic = true
    }
    return literals to dynamic
  }

  private fun directStringArgument(node: TSNode): TSNode? {
    if (!node.canAccess()) return null
    if (node.type == "string_literal" || node.type == "string") return node
    if (node.namedChildCount != 1) return null
    val child = node.getNamedChild(0)
    return child.takeIf { it.canAccess() && (it.type == "string_literal" || it.type == "string") }
  }

  private fun findDescendant(node: TSNode, predicate: (TSNode) -> Boolean): TSNode? {
    if (!node.canAccess()) return null
    for (index in 0 until node.namedChildCount) {
      val child = node.getNamedChild(index)
      if (!child.canAccess()) continue
      if (predicate(child)) return child
      findDescendant(child, predicate)?.let { return it }
    }
    return null
  }

  private fun describeNode(node: TSNode, depth: Int, output: StringBuilder) {
    if (!node.canAccess()) {
      output.append("  ".repeat(depth)).append("<inaccessible>").append(System.lineSeparator())
      return
    }
    output.append("  ".repeat(depth))
        .append(node.type)
        .append('[').append(offset(node.startByte)).append("..").append(offset(node.endByte)).append(']')
        .append(System.lineSeparator())
    for (index in 0 until node.namedChildCount) {
      describeNode(node.getNamedChild(index), depth + 1, output)
    }
  }

  private fun text(node: TSNode, source: String): String = source.substring(offset(node.startByte), offset(node.endByte))

  private fun offset(bytes: Int): Int {
    require(bytes >= 0 && bytes % 2 == 0) { "Tree-sitter UTF-16 byte offset must be even" }
    return bytes / 2
  }
}
