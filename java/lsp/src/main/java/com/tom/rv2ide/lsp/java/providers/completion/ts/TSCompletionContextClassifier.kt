package com.tom.rv2ide.lsp.java.providers.completion.ts

import com.tom.rv2ide.lsp.java.parser.ts.TSJavaParser
import com.tom.rv2ide.treesitter.TSNode
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import java.net.URI
import java.nio.file.Path

object TSCompletionContextClassifier {

  @JvmStatic
  fun classify(file: Path, content: String, cursor: Long): TSCompletionContext {
    if (cursor < 0 || cursor > content.length) {
      return TSCompletionContext.UNKNOWN
    }

    val parseResult = TSJavaParser.parse(InMemoryJavaFileObject(file, content))
    val root = parseResult.tree.rootNode
    val byteOffset = (cursor * 2L).toInt()
    val node = root.getNamedDescendantForByteRange(byteOffset, byteOffset)
    if (node == null || node.isNull()) {
      return TSCompletionContext.UNKNOWN
    }

    return classifyNode(node)
  }

  private fun classifyNode(node: TSNode): TSCompletionContext {
    var current: TSNode? = node
    while (current != null) {
      when (current.getType()) {
        "line_comment", "block_comment", "comment", "string_literal", "character_literal" -> {
          return TSCompletionContext.COMMENT_OR_STRING
        }
        "import_declaration" -> {
          return TSCompletionContext.IMPORT_DECLARATION
        }
        "package_declaration" -> {
          return TSCompletionContext.PACKAGE_DECLARATION
        }
        "field_access", "member_select" -> {
          return TSCompletionContext.MEMBER_ACCESS
        }
        "argument_list" -> {
          return TSCompletionContext.METHOD_CALL_ARGUMENTS
        }
        "block" -> {
          if (hasAncestorOfType(current.getParent(), "method_declaration", "constructor_declaration")) {
            return TSCompletionContext.METHOD_BODY
          }
        }
        "class_body", "interface_body", "enum_body", "annotation_type_body" -> {
          return TSCompletionContext.TYPE_BODY
        }
        "ERROR" -> {
          return TSCompletionContext.BROKEN_SYNTAX_NEAR_CURSOR
        }
      }
      current = current.getParent()
    }
    return TSCompletionContext.UNKNOWN
  }

  private fun hasAncestorOfType(node: TSNode?, vararg types: String): Boolean {
    var current = node
    while (current != null) {
      if (types.any { it == current.getType() }) {
        return true
      }
      current = current.getParent()
    }
    return false
  }

  private class InMemoryJavaFileObject(
    private val path: Path,
    private val content: String,
  ) : SimpleJavaFileObject(path.toUri(), JavaFileObject.Kind.SOURCE) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = content
    override fun getName(): String = path.toString()
    override fun getLastModified(): Long = 0L
    override fun toUri(): URI = path.toUri()
  }
}
