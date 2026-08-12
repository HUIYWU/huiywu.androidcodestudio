package com.tom.rv2ide.lsp.java.providers.completion.ts

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSPoint
import com.itsaky.androidide.treesitter.java.TSLanguageJava
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Identifies source regions where Java semantic completion must be suppressed.
 *
 * This is deliberately not a general completion-context classifier. Import/package routing,
 * member access, method arguments, body/type context, and broken-syntax classification belong to
 * the Javac completion path or its conservative fallback and are intentionally not modeled here.
 */
object TSCompletionSuppressionClassifier {
  private val log = LoggerFactory.getLogger(TSCompletionSuppressionClassifier::class.java)

  @JvmStatic
  fun classify(
      file: Path,
      content: String,
      cursor: Long,
      line: Int,
      column: Int,
  ): TSCompletionSuppressionReason {
    if (cursor < 0 || cursor > content.length || line < 0 || column < 0) {
      return TSCompletionSuppressionReason.NONE
    }
    return classifyAtPosition(file, content, cursor.toInt(), line, column)
  }

  private fun classifyAtPosition(
      file: Path,
      content: String,
      offset: Int,
      line: Int,
      column: Int,
  ): TSCompletionSuppressionReason {
    val uri = file.toUri()
    val logIde = IdeLogConfig.shouldLogIde()
    val totalStartedNs = if (logIde) System.nanoTime() else 0L
    val parserStartedNs = if (logIde) System.nanoTime() else 0L
    TSParser.create().use { parser ->
      parser.language = TSLanguageJava.getInstance()
      val parserCreateUs = if (logIde) (System.nanoTime() - parserStartedNs) / 1_000L else 0L
      val parseStartedNs = if (logIde) System.nanoTime() else 0L
      parser.parseString(content).use { tree ->
        val parseUs = if (logIde) (System.nanoTime() - parseStartedNs) / 1_000L else 0L
        val root = try {
          tree.rootNode
        } catch (err: Throwable) {
          if (IdeLogConfig.shouldLogInfo()) {
            log.info(
                "TSCompletionSuppressionClassifier.rootNodeFailed file={} uri={} errorType={} errorMessage={}",
                file,
                uri,
                err.javaClass.name,
                err.message,
            )
          }
          throw err
        }
        val lookupStartedNs = if (logIde) System.nanoTime() else 0L
        val byteColumn = column * 2
        val node = try {
          root.getNamedDescendantForPointRange(
              TSPoint.create(line, byteColumn),
              TSPoint.create(line, byteColumn + 2),
          )
        } catch (err: Throwable) {
          if (IdeLogConfig.shouldLogInfo()) {
            log.info(
                "TSCompletionSuppressionClassifier.descendantLookupFailed file={} cursor={} uri={} line={} column={} errorType={} errorMessage={}",
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
        val lookupUs = if (logIde) (System.nanoTime() - lookupStartedNs) / 1_000L else 0L
        val result = if (node == null || !node.canAccess()) {
          TSCompletionSuppressionReason.NONE
        } else {
          classifyNode(file, offset.toLong(), uri, node)
        }
        if (logIde) {
          log.debug(
              "JAVA_TS_SUPPRESSION_CLASSIFIER file={} contentLength={} parserCreateUs={} parseUs={} lookupUs={} totalUs={} reason={}",
              file,
              content.length,
              parserCreateUs,
              parseUs,
              lookupUs,
              (System.nanoTime() - totalStartedNs) / 1_000L,
              result,
          )
        }
        return result
      }
    }
  }

  private fun classifyNode(
      file: Path,
      cursor: Long,
      uri: java.net.URI,
      node: TSNode,
  ): TSCompletionSuppressionReason {
    var current: TSNode? = node
    while (current != null) {
      val type = try {
        current.getType()
      } catch (err: Throwable) {
        if (IdeLogConfig.shouldLogInfo()) {
          log.info(
              "TSCompletionSuppressionClassifier.nodeTypeFailed file={} cursor={} uri={} errorType={} errorMessage={}",
              file,
              cursor,
              uri,
              err.javaClass.name,
              err.message,
          )
        }
        throw err
      }
      when (type) {
        "line_comment", "block_comment", "comment" ->
            return TSCompletionSuppressionReason.COMMENT
        "string_literal" -> return TSCompletionSuppressionReason.STRING_LITERAL
        "character_literal" -> return TSCompletionSuppressionReason.CHARACTER_LITERAL
      }
      current = try {
        current.getParent()
      } catch (err: Throwable) {
        if (IdeLogConfig.shouldLogInfo()) {
          log.info(
              "TSCompletionSuppressionClassifier.parentAdvanceFailed file={} cursor={} uri={} errorType={} errorMessage={}",
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
    return TSCompletionSuppressionReason.NONE
  }
}
