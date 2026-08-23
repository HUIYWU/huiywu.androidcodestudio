package com.tom.rv2ide.projects.gradleedit

import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSTree
import com.itsaky.androidide.treesitter.groovy.TSLanguageGroovy
import com.itsaky.androidide.treesitter.kotlin.TSLanguageKotlin

internal object GradleParser {
  data class Literal(val value: String, val start: Int, val end: Int)
  data class Call(val name: String, val start: Int, val end: Int, val arguments: List<Literal>?, val dynamic: Boolean)

  fun parse(source: String, dsl: GradleDsl): List<Call> {
    val parser = TSParser.create()
    return try {
      parser.setLanguage(if (dsl == GradleDsl.KOTLIN) TSLanguageKotlin.getInstance() else TSLanguageGroovy.getInstance())
      val tree = parser.parseString(source) ?: return emptyList()
      try {
        val result = mutableListOf<Call>()
        visit(tree.rootNode, source, result)
        return result
      } finally {
        tree.close()
      }
    } finally {
      parser.close()
    }
  }

  private fun visit(node: TSNode, source: String, result: MutableList<Call>) {
    val type = node.type
    if (type == "call_expression" || type == "method_invocation") {
      val name = callName(node, source)
      if (name != null) {
        val arguments = argumentNode(node)?.let { argumentList(it, source) }
        result += Call(name, offset(node.startByte), offset(node.endByte), arguments?.first, arguments?.second == true)
      }
    }
    for (index in 0 until node.namedChildCount) {
      val child = node.getNamedChild(index)
      if (!child.isNull()) visit(child, source, result)
    }
  }

  private fun callName(node: TSNode, source: String): String? {
    if (node.type == "call_expression") {
      val function = node.getChildByFieldName("function")
      if (!function.isNull() && function.type == "simple_identifier") return text(function, source)
      val callee = node.getNamedChild(0)
      return if (!callee.isNull() && callee.type == "simple_identifier") text(callee, source) else null
    }
    if (node.type == "method_invocation") {
      val function = node.getChildByFieldName("function")
      if (!function.isNull() && (function.type == "identifier" || function.type == "simple_identifier")) return text(function, source)
    }
    return null
  }

  private fun argumentNode(node: TSNode): TSNode? {
    val field = node.getChildByFieldName("arguments")
    if (!field.isNull()) return field
    return findDescendant(node) { it.type == "value_arguments" || it.type == "argument_list" }
  }

  private fun argumentList(node: TSNode, source: String): Pair<List<Literal>, Boolean> {
    val literals = mutableListOf<Literal>()
    var dynamic = false
    for (index in 0 until node.namedChildCount) {
      val argument = node.getNamedChild(index)
      if (argument.isNull()) continue
      val string = when (argument.type) {
        "string_literal", "string" -> argument
        else -> findDescendant(argument) { it.type == "string_literal" || it.type == "string" }
      }
      if (string == null || string.startByte != argument.startByte || string.endByte != argument.endByte) {
        dynamic = true
        continue
      }
      val raw = text(string, source)
      val value = raw.substringAfter("\"").substringBeforeLast("\"")
        .takeIf { raw.startsWith("\"") } ?: raw.substringAfter("'").substringBeforeLast("'")
      if (value.contains("\$")) dynamic = true else literals += Literal(value, offset(string.startByte), offset(string.endByte))
    }
    return literals to dynamic
  }

  private fun findDescendant(node: TSNode, predicate: (TSNode) -> Boolean): TSNode? {
    for (index in 0 until node.namedChildCount) {
      val child = node.getNamedChild(index)
      if (!child.isNull()) {
        if (predicate(child)) return child
        findDescendant(child, predicate)?.let { return it }
      }
    }
    return null
  }

  private fun text(node: TSNode, source: String): String = source.substring(offset(node.startByte), offset(node.endByte))

  private fun offset(bytes: Int): Int {
    require(bytes >= 0 && bytes % 2 == 0) { "Tree-sitter UTF-16 byte offset must be even" }
    return bytes / 2
  }
}
