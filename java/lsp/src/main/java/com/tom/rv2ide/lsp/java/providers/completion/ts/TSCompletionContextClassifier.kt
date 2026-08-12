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
    if (cursor < 0 || cursor > content.length || line < 0 || column < 0) {
      return TSCompletionContext.UNKNOWN
    }
    return classifyAtPositions(file, content, listOf(Position(cursor.toInt(), line, column))).single()
  }

  /**
   * Classifies multiple UTF-16 offsets from one immutable source string using one native parse.
   * Callers that need more than one cursor position should prefer this over repeated [classify]
   * calls, because every individual call otherwise creates a parser and parses the full content.
   */
  @JvmStatic
  fun classifyOffsets(file: Path, content: String, offsets: IntArray): List<TSCompletionContext> {
    if (offsets.isEmpty()) return emptyList()
    if (offsets.any { it < 0 || it > content.length }) {
      return List(offsets.size) { TSCompletionContext.UNKNOWN }
    }
    val positions =
        offsets.map { offset ->
          val line = content.substring(0, offset).count { it == '\n' }
          val lineStart = content.lastIndexOf('\n', offset - 1) + 1
          Position(offset, line, offset - lineStart)
        }
    return classifyAtPositions(file, content, positions)
  }

  private fun classifyAtPositions(
      file: Path,
      content: String,
      positions: List<Position>,
  ): List<TSCompletionContext> {
    val uri = file.toUri()
    val logIde = IdeLogConfig.shouldLogIde()
    val totalStartedNs = if (logIde) System.nanoTime() else 0L
    val parserStartedNs = if (logIde) System.nanoTime() else 0L
    TSParser.create().use { parser ->
      parser.language = TSLanguageJava.getInstance()
      val parserCreateUs =
          if (logIde) (System.nanoTime() - parserStartedNs) / 1_000L else 0L
      val parseStartedNs = if (logIde) System.nanoTime() else 0L
      parser.parseString(content).use { tree ->
        val parseUs = if (logIde) (System.nanoTime() - parseStartedNs) / 1_000L else 0L
        val rootStartedNs = if (logIde) System.nanoTime() else 0L
        val root = try {
          tree.rootNode
        } catch (err: Throwable) {
          if (IdeLogConfig.shouldLogInfo()) {
            log.info(
                "TSCompletionContextClassifier.rootNodeFailed file={} uri={} errorType={} errorMessage={}",
                file,
                uri,
                err.javaClass.name,
                err.message,
            )
          }
          throw err
        }
        val rootUs = if (logIde) (System.nanoTime() - rootStartedNs) / 1_000L else 0L
        val positionsStartedNs = if (logIde) System.nanoTime() else 0L
        val result = positions.map { position -> classifyPosition(file, position, uri, root) }
        val positionsUs =
            if (logIde) (System.nanoTime() - positionsStartedNs) / 1_000L else 0L
        if (logIde) {
          log.debug(
              "JAVA_TS_CLASSIFIER file={} contentLength={} positionCount={} parserCreateUs={} parseUs={} rootUs={} positionsUs={} totalUs={} context={}",
              file,
              content.length,
              positions.size,
              parserCreateUs,
              parseUs,
              rootUs,
              positionsUs,
              (System.nanoTime() - totalStartedNs) / 1_000L,
              result.singleOrNull() ?: result,
          )
        }
        return result
      }
    }
  }

  private fun classifyPosition(
      file: Path,
      position: Position,
      uri: URI,
      root: TSNode,
  ): TSCompletionContext {
    val offset = position.offset
    val line = position.line
    val column = position.column
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
            offset,
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
    return classifyNode(file, offset.toLong(), uri, node)
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
        "line_comment", "block_comment", "comment" -> {
          return TSCompletionContext.COMMENT
        }
        "string_literal" -> {
          return TSCompletionContext.STRING_LITERAL
        }
        "character_literal" -> {
          return TSCompletionContext.CHARACTER_LITERAL
        }
        "import_declaration", "import" -> {
          return TSCompletionContext.IMPORT_DECLARATION
        }
        "package_declaration", "package" -> {
          return TSCompletionContext.PACKAGE_DECLARATION
        }
        "field_access" -> {
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
          if (hasAncestorOfType(parent, "method_declaration", "compact_constructor_declaration")) {
            return TSCompletionContext.METHOD_BODY
          }
        }
        "constructor_body" -> {
          // The Java grammar uses constructor_body rather than block for ordinary constructors.
          return TSCompletionContext.METHOD_BODY
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

  private data class Position(val offset: Int, val line: Int, val column: Int)

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
