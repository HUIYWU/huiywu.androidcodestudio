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
    val node = findSmallestNodeContaining(root, byteOffset) ?: return TSCompletionContext.UNKNOWN

    return classifyNode(node)
  }

  private fun classifyNode(node: TSNode): TSCompletionContext {
    var current: TSNode? = node
    while (current != null) {
      when (current.type) {
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
          if (hasAncestorOfType(current.parent, "method_declaration", "constructor_declaration")) {
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
      current = current.parent
    }
    return TSCompletionContext.UNKNOWN
  }

  private fun hasAncestorOfType(node: TSNode?, vararg types: String): Boolean {
    var current = node
    while (current != null) {
      if (types.any { it == current.type }) {
        return true
      }
      current = current.parent
    }
    return false
  }

  private fun findSmallestNodeContaining(node: TSNode, byteOffset: Int): TSNode? {
    if (byteOffset < node.startByte || byteOffset > node.endByte) {
      return null
    }

    var child = node.firstChild
    while (child != null) {
      val match = findSmallestNodeContaining(child, byteOffset)
      if (match != null) {
        return match
      }
      child = child.nextSibling
    }

    return node
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
