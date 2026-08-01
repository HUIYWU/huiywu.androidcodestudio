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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.xml.diagnostics.rules

import com.tom.rv2ide.lsp.xml.diagnostics.ClosingTagMismatchDiagnosticData
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticCollector
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticContext
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticMessages
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticRule
import java.io.StringReader
import java.util.ArrayDeque
import java.util.Locale
import java.util.MissingResourceException
import jaxp.sun.org.apache.xerces.internal.impl.XMLErrorReporter
import jaxp.sun.org.apache.xerces.internal.impl.msg.XMLMessageFormatter
import jaxp.sun.org.apache.xerces.internal.impl.xs.opti.DefaultXMLDocumentHandler
import jaxp.sun.org.apache.xerces.internal.parsers.XIncludeAwareParserConfiguration
import jaxp.sun.org.apache.xerces.internal.util.MessageFormatter
import jaxp.sun.org.apache.xerces.internal.utils.XMLSecurityManager
import jaxp.sun.org.apache.xerces.internal.utils.XMLSecurityPropertyManager
import jaxp.sun.org.apache.xerces.internal.xni.XNIException
import jaxp.sun.org.apache.xerces.internal.xni.parser.XMLEntityResolver
import jaxp.sun.org.apache.xerces.internal.xni.parser.XMLErrorHandler
import jaxp.sun.org.apache.xerces.internal.xni.parser.XMLInputSource
import jaxp.sun.org.apache.xerces.internal.xni.parser.XMLParseException
import org.slf4j.LoggerFactory

/** XML005: relocated composite Xerces errors not already covered by XML001-XML003. */
internal object XmlParserDiagnosticRule : XmlDiagnosticRule {
  override val id: String = "xml-parser"

  override fun supports(context: XmlDiagnosticContext): Boolean = true

  override fun diagnose(context: XmlDiagnosticContext, collector: XmlDiagnosticCollector) {
    // Composite Xerces is an optional syntax enhancement. Any configuration/runtime incompatibility
    // must degrade to the existing tolerant DOM diagnostics instead of aborting the document pass.
    val errors = runCatching { parse(context) }.getOrElse { return }
    collect(errors, context, collector)
  }

  /** Strict test entry point: exposes parser/configuration failures instead of silently degrading. */
  internal fun diagnoseStrictForTest(
      context: XmlDiagnosticContext,
      collector: XmlDiagnosticCollector,
  ) {
    collect(parse(context), context, collector)
  }

  private fun collect(
      errors: List<ParserError>,
      context: XmlDiagnosticContext,
      collector: XmlDiagnosticCollector,
  ) {
    errors.forEach { error ->
      val offset = errorOffset(error, context.text)
      val endTagMismatch =
          if (error.key == E_TAG_NAME_MISMATCH) {
            error.toEndTagMismatch(context.text) ?: findFirstEndTagMismatch(context.text)
          } else if (error.key in END_TAG_NAME_ERROR_KEYS) {
            findFirstEndTagMismatch(context.text)
          } else {
            null
          }
      // Xerces characterOffset is authoritative after XMLEntityScanner's buffer accounting fix.
      // The line/column-derived offset is passed only as a compatibility fallback for parser
      // builds that do not provide a usable character offset.
      val range =
          endTagMismatch?.actualNameRange
              ?: parserErrorRange(
                  error.key,
                  offset,
                  context.text,
                  if (error.characterOffset > 0) null
                  else lineColumnOffset(error.line, error.column, context.text),
              )
      // Older parser builds use ETagUnterminated when the actual closing name starts with the
      // expected name (for example </LinearLayout> for <LinearLayou>). Preserve that fallback
      // only when source reconstruction proves it is a mismatch rather than a missing `>`. Newer
      // AndroidCodeStudio Xerces builds emit ETagNameMismatch with expected/actual arguments.
      if (error.key in ERRORS_COVERED_BY_TOLERANT_DOM && endTagMismatch == null) {
        return@forEach
      }
      collector.errorRange(
          code = CODE_XML_PARSER_SYNTAX,
          message = when {
            endTagMismatch != null ->
                XmlDiagnosticMessages.closingTagMismatch(
                    endTagMismatch.actualName, endTagMismatch.expectedName)
            else -> error.message
          },
          start = range.first,
          end = range.last + 1,
          extra = when {
            endTagMismatch != null ->
                ClosingTagMismatchDiagnosticData(
                    endTagMismatch.actualName, endTagMismatch.expectedName)
            else -> Any()
          },
      )
    }
  }

  private fun parse(context: XmlDiagnosticContext): List<ParserError> {
    val errors = mutableListOf<ParserError>()
    val configuration = XIncludeAwareParserConfiguration()
    val noOpHandler = DefaultXMLDocumentHandler()
    configuration.documentHandler = noOpHandler
    configuration.dtdHandler = noOpHandler
    configuration.dtdContentModelHandler = noOpHandler
    installSecurityManagers(configuration)
    installI18nFormatterWithKeyFallback(configuration)
    configureSafely(configuration, FEATURE_VALIDATION, false)
    configureSafely(configuration, FEATURE_SCHEMA_VALIDATION, false)
    configureSafely(configuration, FEATURE_LOAD_EXTERNAL_DTD, false)
    configureSafely(configuration, FEATURE_EXTERNAL_GENERAL_ENTITIES, false)
    configureSafely(configuration, FEATURE_EXTERNAL_PARAMETER_ENTITIES, false)
    configureSafely(configuration, FEATURE_XINCLUDE, false)
    configureSafely(configuration, FEATURE_DISALLOW_DOCTYPE, true)
    // Composite Xerces is based on an older parser branch. Stop after a fatal error instead of
    // continuing through a corrupted scanner state and potentially discarding the captured error.
    configureSafely(configuration, FEATURE_CONTINUE_AFTER_FATAL_ERROR, false)
    configuration.entityResolver = XMLEntityResolver { identifier ->
      XMLInputSource(
          identifier.publicId,
          identifier.literalSystemId,
          identifier.baseSystemId,
          StringReader(""),
          null,
      )
    }
    configuration.errorHandler =
        object : XMLErrorHandler {
          override fun warning(domain: String?, key: String?, exception: XMLParseException) = Unit

          override fun error(domain: String?, key: String?, exception: XMLParseException) {
            addError(errors, key, exception)
          }

          override fun fatalError(domain: String?, key: String?, exception: XMLParseException) {
            addError(errors, key, exception)
          }
        }

    val input =
        XMLInputSource(
            null,
            context.file.toUri().toString(),
            null,
            StringReader(context.text),
            null,
        )
    try {
      configuration.parse(input)
    } catch (error: Exception) {
      // Fatal syntax errors are delivered before parsing stops. Preserve those captured errors even
      // if the old relocated parser subsequently wraps the stop condition in another exception.
      if (errors.isEmpty()) {
        throw error
      }
    } catch (error: LinkageError) {
      // Preserve already delivered diagnostics, but let the caller safely degrade when initialization
      // or class linking failed before the XNI handler received anything.
      if (errors.isEmpty()) {
        throw error
      }
    }
    return errors
  }

  private fun addError(
      errors: MutableList<ParserError>,
      key: String?,
      exception: XMLParseException,
  ) {
    if (errors.size >= MAX_PARSER_ERRORS) {
      return
    }
    errors +=
        ParserError(
            key = key.orEmpty(),
            // Composite Xerces formats this message with Locale.getDefault() and its i18n bundles.
            message = exception.message ?: "Invalid XML syntax",
            arguments = exception.arguments?.toList().orEmpty(),
            characterOffset = exception.characterOffset,
            line = exception.lineNumber,
            column = exception.columnNumber,
        )
  }

private fun errorOffset(error: ParserError, text: String): Int {
     // Xerces characterOffset is the primary source after fixing XMLEntityScanner.load(). Keep the
     // line/column locator as a compatibility fallback for malformed input or older parser builds.
     if (error.characterOffset > 0) {
       return (error.characterOffset - 1).coerceIn(0, text.length)
     }
     return lineColumnOffset(error.line, error.column, text) ?: 0
   }

   /** Converts Xerces' one-based line/column locator to a zero-based document offset. */
   private fun lineColumnOffset(line: Int, column: Int, text: String): Int? {
     if (line <= 0 || column <= 0 || text.isEmpty()) return null
     var currentLine = 1
     var lineStart = 0
     var index = 0
     while (index < text.length && currentLine < line) {
       if (text[index] == '\n') {
         currentLine++
         lineStart = index + 1
       }
       index++
     }
     if (currentLine != line) return null
     return (lineStart + column - 1).coerceIn(0, text.length)
   }

   private fun parserErrorRange(
      key: String,
      offset: Int,
      text: String,
      lineColumnOffset: Int? = null,
  ): IntRange {
    if (text.isEmpty()) {
      return 0..0
    }
    val safeOffset = offset.coerceIn(0, text.lastIndex)
    val fallbackOffset = lineColumnOffset?.coerceIn(0, text.lastIndex) ?: safeOffset
    return when (key) {
      "EqRequiredInAttribute" ->
          findMalformedAttributeName(text, safeOffset, requireMissingEquals = true)
              ?: selectNameAround(safeOffset, text)
      "OpenQuoteExpected" ->
          findMalformedAttributeName(text, safeOffset, requireMissingEquals = false)
              ?: selectNameAround(safeOffset, text)
      "AttributeNotUnique" -> selectNameAround(safeOffset, text)
      "LessthanInAttValue" -> findLessThanInAttributeValue(text, fallbackOffset)
      "DashDashInComment" -> findInvalidCommentDashRange(text, fallbackOffset)
      MARKUP_NOT_RECOGNIZED_IN_CONTENT -> findMalformedMarkupRange(text, fallbackOffset)
      else -> safeOffset..safeOffset
    }
  }

private fun findLessThanInAttributeValue(text: String, locatorOffset: Int): IntRange {
     val lineStart = text.lastIndexOf('\n', (locatorOffset - 1).coerceAtLeast(0)) + 1
     val lineEnd = text.indexOf('\n', locatorOffset).let { if (it < 0) text.length else it }
     // Xerces may locate this error on the character immediately before '<'. Prefer the first
     // less-than at or after that locator on the same line, then fall back to a preceding one.
     val forward = text.indexOf('<', locatorOffset).takeIf { it in lineStart until lineEnd }
     val lessThan = forward ?: text.lastIndexOf('<', locatorOffset.coerceAtMost(lineEnd - 1))
     return if (lessThan >= lineStart) lessThan..lessThan else locatorOffset..locatorOffset
   }

  private fun findInvalidCommentDashRange(text: String, locatorOffset: Int): IntRange {
    val lineStart = text.lastIndexOf('\n', (locatorOffset - 1).coerceAtLeast(0)) + 1
    val lineEnd = text.indexOf('\n', locatorOffset).let { if (it < 0) text.length else it }
    val commentStart = text.lastIndexOf("<!--", locatorOffset.coerceAtMost(lineEnd))
    if (commentStart < lineStart) return locatorOffset..locatorOffset
    val commentEnd = text.indexOf("--", commentStart + 4).takeIf { it in commentStart until lineEnd }
    return if (commentEnd != null) commentEnd..(commentEnd + 1) else locatorOffset..locatorOffset
  }

  private fun findMalformedMarkupRange(text: String, locatorOffset: Int): IntRange {
    val lineStart = text.lastIndexOf('\n', (locatorOffset - 1).coerceAtLeast(0)) + 1
    val lineEnd = text.indexOf('\n', locatorOffset).let { if (it < 0) text.length else it }
    val lessThan = text.indexOf('<', lineStart).takeIf { it in lineStart until lineEnd }
    if (lessThan != null) {
      val greaterThan = text.indexOf('>', lessThan + 1)
      return if (greaterThan in (lessThan + 1) until lineEnd) {
        lessThan..greaterThan
      } else {
        lessThan..lessThan
      }
    }
    return locatorOffset..locatorOffset
  }

  private fun findMalformedAttributeName(
      text: String,
      parserOffset: Int,
      requireMissingEquals: Boolean,
  ): IntRange? {
    var cursor = parserOffset.coerceIn(0, text.lastIndex)
    val minimumOffset = (cursor - MAX_ATTRIBUTE_LOOKBACK_CHARS).coerceAtLeast(0)
    repeat(MAX_ATTRIBUTE_LOOKBACK_TAGS) {
      val tagStart = text.lastIndexOf('<', cursor)
      if (tagStart < minimumOffset) {
        return null
      }
      cursor = tagStart - 1
      val marker = text.getOrNull(tagStart + 1)
      if (marker == null || marker == '/' || marker == '!' || marker == '?') {
        return@repeat
      }
      val tagEnd = findStartTagEnd(text, tagStart)
      if (tagEnd < 0) {
        return@repeat
      }
      findMalformedAttributeInTag(text, tagStart, tagEnd, requireMissingEquals)?.let {
        return it
      }
    }
    return null
  }

  /** Finds `>` outside quoted attribute values. */
  private fun findStartTagEnd(text: String, tagStart: Int): Int {
    var quote: Char? = null
    for (index in tagStart + 1 until text.length) {
      val character = text[index]
      if (quote == null) {
        if (character in QUOTES) {
          quote = character
        } else if (character == '>') {
          return index
        } else if (character == '<') {
          return -1
        }
      } else if (character == quote) {
        quote = null
      }
    }
    return -1
  }

  private fun findMalformedAttributeInTag(
      text: String,
      tagStart: Int,
      tagEnd: Int,
      requireMissingEquals: Boolean,
  ): IntRange? {
    var index = tagStart + 1
    // Skip the element name before inspecting attributes.
    while (index < tagEnd && !text[index].isWhitespace()) {
      index++
    }
    while (index < tagEnd) {
      while (index < tagEnd && text[index].isWhitespace()) {
        index++
      }
      if (index >= tagEnd || text[index] == '/') {
        break
      }
      val nameStart = index
      while (index < tagEnd && isXmlNameCharacter(text[index])) {
        index++
      }
      if (index == nameStart) {
        index++
        continue
      }
      val nameEnd = index
      while (index < tagEnd && text[index].isWhitespace()) {
        index++
      }
      if (requireMissingEquals && index < tagEnd && text[index] in QUOTES) {
        return nameStart until nameEnd
      }
      if (!requireMissingEquals && index < tagEnd && text[index] == '=') {
        var valueStart = index + 1
        while (valueStart < tagEnd && text[valueStart].isWhitespace()) {
          valueStart++
        }
        if (valueStart < tagEnd && text[valueStart] !in QUOTES) {
          return nameStart until nameEnd
        }
      }
      // Skip a quoted value so names inside the value are not treated as attributes.
      if (index < tagEnd && text[index] == '=') {
        index++
        while (index < tagEnd && text[index].isWhitespace()) {
          index++
        }
      }
      if (index < tagEnd && text[index] in QUOTES) {
        val quote = text[index++]
        while (index < tagEnd && text[index] != quote) {
          index++
        }
        if (index < tagEnd) {
          index++
        }
      }
    }
    return null
  }

  private fun selectNameAround(offset: Int, text: String): IntRange {
    var start = offset
    while (start > 0 && isXmlNameCharacter(text[start - 1])) {
      start--
    }
    var end = offset
    while (end < text.lastIndex && isXmlNameCharacter(text[end + 1])) {
      end++
    }
    return start..end
  }

  /**
   * Replays well-formed tag boundaries from the document start and returns the first closing name
   * that differs from the stack top. Xerces stops on that same first mismatch, while its reported
   * character offset is not stable across line wrapping and formatted documents.
   */
  private fun ParserError.toEndTagMismatch(text: String): EndTagMismatch? {
    val expectedName = arguments.getOrNull(0)?.toString() ?: return null
    val actualName = arguments.getOrNull(1)?.toString() ?: return null
    return findFirstEndTagMismatchFor(text, expectedName, actualName)
  }

  private fun findFirstEndTagMismatch(text: String): EndTagMismatch? {
    return findFirstEndTagMismatchFor(text, expectedName = null, actualName = null)
  }

  private fun findFirstEndTagMismatchFor(
      text: String,
      expectedName: String?,
      actualName: String?,
  ): EndTagMismatch? {
    val stack = ArrayDeque<String>()
    var index = 0
    while (index < text.length) {
      if (text[index] != '<') {
        index++
        continue
      }
      when {
        text.startsWith("<!--", index) -> {
          val end = text.indexOf("-->", index + 4)
          if (end < 0) return null
          index = end + 3
        }
        text.startsWith("<![CDATA[", index) -> {
          val end = text.indexOf("]]>", index + 9)
          if (end < 0) return null
          index = end + 3
        }
        text.startsWith("<?", index) -> {
          val end = text.indexOf("?>", index + 2)
          if (end < 0) return null
          index = end + 2
        }
        text.startsWith("</", index) -> {
          val closingTag = readClosingTag(text, index) ?: return null
          val stackExpectedName = stack.lastOrNull() ?: return null
          if (stackExpectedName != closingTag.name) {
            if ((expectedName == null || expectedName == stackExpectedName) &&
                (actualName == null || actualName == closingTag.name)) {
              return EndTagMismatch(
                  stackExpectedName,
                  closingTag.name,
                  closingTag.nameStart until closingTag.nameEnd,
              )
            }
            return null
          }
          stack.removeLast()
          index = closingTag.tagEnd + 1
        }
        text.startsWith("<!", index) -> {
          val end = text.indexOf('>', index + 2)
          if (end < 0) return null
          index = end + 1
        }
        else -> {
          val nameStart = index + 1
          var nameEnd = nameStart
          while (nameEnd < text.length && isXmlNameCharacter(text[nameEnd])) nameEnd++
          val end = findStartTagEnd(text, index)
          if (nameEnd == nameStart || end < 0) return null
          if (text.substring(index + 1, end).trimEnd().endsWith('/').not()) {
            stack.addLast(text.substring(nameStart, nameEnd))
          }
          index = end + 1
        }
      }
    }
    return null
  }

  private fun readClosingTag(text: String, tagStart: Int): ClosingTag? {
    val nameStart = tagStart + 2
    var nameEnd = nameStart
    while (nameEnd < text.length && isXmlNameCharacter(text[nameEnd])) nameEnd++
    var tagEnd = nameEnd
    while (tagEnd < text.length && text[tagEnd].isWhitespace()) tagEnd++
    if (nameEnd == nameStart || tagEnd >= text.length || text[tagEnd] != '>') return null
    return ClosingTag(nameStart, nameEnd, tagEnd, text.substring(nameStart, nameEnd))
  }

  private fun isXmlNameCharacter(character: Char): Boolean {
    return character.isLetterOrDigit() ||
        character == '_' ||
        character == '-' ||
        character == ':' ||
        character == '.'
  }

  private fun installSecurityManagers(configuration: XIncludeAwareParserConfiguration) {
    configuration.setProperty(PROPERTY_SECURITY_MANAGER, XMLSecurityManager(true))
    val propertyManager = XMLSecurityPropertyManager()
    propertyManager.setValue(
        XMLSecurityPropertyManager.Property.ACCESS_EXTERNAL_DTD,
        XMLSecurityPropertyManager.State.APIPROPERTY,
        "",
    )
    propertyManager.setValue(
        XMLSecurityPropertyManager.Property.ACCESS_EXTERNAL_SCHEMA,
        XMLSecurityPropertyManager.State.APIPROPERTY,
        "",
    )
    configuration.setProperty(PROPERTY_XML_SECURITY_PROPERTY_MANAGER, propertyManager)
  }

  private fun installI18nFormatterWithKeyFallback(
      configuration: XIncludeAwareParserConfiguration,
  ) {
    val reporter =
        configuration.getProperty(PROPERTY_ERROR_REPORTER) as? XMLErrorReporter ?: return
    val delegate = XMLMessageFormatter()
    val formatter =
        object : MessageFormatter {
          override fun formatMessage(
              locale: Locale?,
              key: String,
              arguments: Array<out Any?>?,
          ): String {
            return try {
              delegate.formatMessage(locale, key, arguments)
            } catch (_: MissingResourceException) {
              fallbackMessage(key, arguments)
            }
          }
        }
    reporter.putMessageFormatter(XMLMessageFormatter.XML_DOMAIN, formatter)
    reporter.putMessageFormatter(XMLMessageFormatter.XMLNS_DOMAIN, formatter)
  }

  private fun fallbackMessage(key: String, arguments: Array<out Any?>?): String {
    if (arguments.isNullOrEmpty()) {
      return key
    }
    return "$key: ${arguments.joinToString()}"
  }

  private fun configureSafely(
      configuration: XIncludeAwareParserConfiguration,
      feature: String,
      enabled: Boolean,
  ) {
    try {
      configuration.setFeature(feature, enabled)
    } catch (_: XNIException) {
      // The relocated JAXP branch may not expose every optional feature on every configuration.
    }
  }

  private data class ParserError(
      val key: String,
      val message: String,
      val arguments: List<Any?>,
      val characterOffset: Int,
      val line: Int,
      val column: Int,
  )

  private data class ClosingTag(
      val nameStart: Int,
      val nameEnd: Int,
      val tagEnd: Int,
      val name: String,
  )

  private data class EndTagMismatch(
      val expectedName: String,
      val actualName: String,
      val actualNameRange: IntRange,
  )

  private const val CODE_XML_PARSER_SYNTAX = "XML005"
  private const val E_TAG_REQUIRED = "ETagRequired"
  private const val E_TAG_NAME_MISMATCH = "ETagNameMismatch"
  private const val E_TAG_UNTERMINATED = "ETagUnterminated"
  private const val MARKUP_NOT_RECOGNIZED_IN_CONTENT = "MarkupNotRecognizedInContent"

  private val END_TAG_NAME_ERROR_KEYS = setOf(E_TAG_REQUIRED, E_TAG_UNTERMINATED)
  private const val MAX_PARSER_ERRORS = 20
  private const val MAX_ATTRIBUTE_LOOKBACK_TAGS = 4
  private const val MAX_ATTRIBUTE_LOOKBACK_CHARS = 4096
  private const val QUOTES = "\"'"
  private const val PROPERTY_ERROR_REPORTER =
      "http://apache.org/xml/properties/internal/error-reporter"
  private const val PROPERTY_SECURITY_MANAGER =
      "http://apache.org/xml/properties/security-manager"
  private const val PROPERTY_XML_SECURITY_PROPERTY_MANAGER =
      "http://www.oracle.com/xml/jaxp/properties/xmlSecurityPropertyManager"
  private const val FEATURE_VALIDATION = "http://xml.org/sax/features/validation"
  private const val FEATURE_SCHEMA_VALIDATION =
      "http://apache.org/xml/features/validation/schema"
  private const val FEATURE_LOAD_EXTERNAL_DTD =
      "http://apache.org/xml/features/nonvalidating/load-external-dtd"
  private const val FEATURE_EXTERNAL_GENERAL_ENTITIES =
      "http://xml.org/sax/features/external-general-entities"
  private const val FEATURE_EXTERNAL_PARAMETER_ENTITIES =
      "http://xml.org/sax/features/external-parameter-entities"
  private const val FEATURE_XINCLUDE = "http://apache.org/xml/features/xinclude"
  private const val FEATURE_DISALLOW_DOCTYPE =
      "http://apache.org/xml/features/disallow-doctype-decl"
  private const val FEATURE_CONTINUE_AFTER_FATAL_ERROR =
      "http://apache.org/xml/features/continue-after-fatal-error"

  private val log = LoggerFactory.getLogger(XmlParserDiagnosticRule::class.java)

  private val ERRORS_COVERED_BY_TOLERANT_DOM =
      setOf(
          "AttributeNotUnique",
          "AttributePrefixUnbound",
          "ElementUnterminated",
          "ETagUnterminated",
          "MarkupEntityMismatch",
          "PrematureEOF",
      )
}