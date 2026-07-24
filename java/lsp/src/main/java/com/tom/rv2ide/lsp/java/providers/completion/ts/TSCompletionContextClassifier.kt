package com.tom.rv2ide.lsp.java.providers.completion.ts

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSPoint
import com.itsaky.androidide.treesitter.java.TSLanguageJava

import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import java.net.URI
import java.nio.file.Path
import org.slf4j.LoggerFactory

object TSCompletionContextClassifier {

  private val log = LoggerFactory.getLogger(TSCompletionContextClassifier::class.java)
  @JvmStatic
  fun classify(file: Path, content: String, cursor: Long, line: Int, column: Int): TSCompletionContext {
    if (cursor < 0 || cursor > content.length) {
      return TSCompletionContext.UNKNOWN
    }
    if (line < 0 || column < 0) {
      return TSCompletionContext.UNKNOWN
    }

    val uri = file.toUri()

    TSParser.create().use { parser ->
      parser.language = TSLanguageJava.getInstance()
      parser.parseString(content).use { tree ->
        val root = try {
          tree.rootNode
        } catch (err: Throwable) {
          if (IdeLogConfig.shouldLogInfo()) {
            log.info(
                "TSCompletionContextClassifier.rootNodeFailed file={} cursor={} uri={} errorType={} errorMessage={}",
                file,
                cursor,
                uri,
                err.javaClass.name,
                err.message,
            )
          }
          throw err
        }
        // TSParser converts Java strings to UTF-16LE. Tree-sitter point columns are byte offsets,
        // while editor columns are UTF-16 code-unit offsets, so each column occupies two bytes.
        // Use a non-empty range to make token lookup deterministic at boundaries.
        val byteColumn = column * 2
        val startPoint = TSPoint.create(line, byteColumn)
        val endPoint = TSPoint.create(line, byteColumn + 2)
        val node = try {
          root.getNamedDescendantForPointRange(startPoint, endPoint)
        } catch (err: Throwable) {
          if (IdeLogConfig.shouldLogInfo()) {
            log.info(
                "TSCompletionContextClassifier.descendantLookupFailed file={} cursor={} uri={} line={} column={} errorType={} errorMessage={}",
                file,
                cursor,
                uri,
                line,
                column,
                err.javaClass.name,
                err.message,
            )
          }
          throw err
        }

        if (node == null || !node.canAccess()) {
          return TSCompletionContext.UNKNOWN
        }

        return classifyNode(file, cursor, uri, node)
      }
    }
  }

  private fun classifyNode(file: Path, cursor: Long, uri: URI, node: TSNode): TSCompletionContext {
    // Tree-sitter context classification is an opportunistic signal layer.
    // The Java completion pipeline must remain usable even if native node access
    // fails intermittently, so callers intentionally wrap this classifier with
    // fallback-to-UNKNOWN behavior.
    var current: TSNode? = node
    while (current != null) {
      val currentType = try {
        current.getType()
      } catch (err: Throwable) {
        if (IdeLogConfig.shouldLogInfo()) {
          log.info(
              "TSCompletionContextClassifier.nodeTypeFailed file={} cursor={} uri={} errorType={} errorMessage={}",
              file,
              cursor,
              uri,
              err.javaClass.name,
              err.message,
          )
        }
        throw err
      }
      when (currentType) {
        "line_comment", "block_comment", "comment", "string_literal", "character_literal" -> {
          return TSCompletionContext.COMMENT_OR_STRING
        }
        "import_declaration", "import" -> {
          return TSCompletionContext.IMPORT_DECLARATION
        }
        "package_declaration", "package" -> {
          return TSCompletionContext.PACKAGE_DECLARATION
        }
        "field_access", "member_select" -> {
          return TSCompletionContext.MEMBER_ACCESS
        }
        "argument_list" -> {
          return TSCompletionContext.METHOD_CALL_ARGUMENTS
        }
        "block" -> {
          val parent = try {
            current.getParent()
          } catch (err: Throwable) {
            if (IdeLogConfig.shouldLogInfo()) {
              log.info(
                  "TSCompletionContextClassifier.parentLookupFailed file={} cursor={} uri={} errorType={} errorMessage={}",
                  file,
                  cursor,
                  uri,
                  err.javaClass.name,
                  err.message,
              )
            }
            throw err
          }
          if (hasAncestorOfType(parent, "method_declaration", "constructor_declaration")) {
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
      current = try {
        current.getParent()
      } catch (err: Throwable) {
        if (IdeLogConfig.shouldLogInfo()) {
          log.info(
              "TSCompletionContextClassifier.parentAdvanceFailed file={} cursor={} uri={} errorType={} errorMessage={}",
              file,
              cursor,
              uri,
              err.javaClass.name,
              err.message,
          )
        }
        throw err
      }
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
