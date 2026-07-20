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

import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticCollector
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticContext
import com.tom.rv2ide.lsp.xml.diagnostics.XmlDiagnosticRule
import java.io.StringReader
import org.apache.xerces.parsers.XIncludeAwareParserConfiguration
import org.apache.xerces.xni.XNIException
import org.apache.xerces.xni.parser.XMLEntityResolver
import org.apache.xerces.xni.parser.XMLErrorHandler
import org.apache.xerces.xni.parser.XMLInputSource
import org.apache.xerces.xni.parser.XMLParseException

/** XML005: Xerces well-formedness errors not already covered by XML001-XML003. */
internal object XmlParserDiagnosticRule : XmlDiagnosticRule {
  override val id: String = "xml-parser"

  override fun supports(context: XmlDiagnosticContext): Boolean = true

  override fun diagnose(context: XmlDiagnosticContext, collector: XmlDiagnosticCollector) {
    // Xerces is an optional syntax enhancement. Any parser/configuration incompatibility must
    // degrade to the existing tolerant DOM diagnostics instead of aborting the document pass.
    val errors = runCatching { parse(context) }.getOrElse { return }
    errors.forEach { error ->
      if (error.key in ERRORS_COVERED_BY_TOLERANT_DOM) {
        return@forEach
      }
      val offset = errorOffset(error, context.text)
      val range = parserErrorRange(error.key, offset, context.text)
      collector.errorRange(
          code = CODE_XML_PARSER_SYNTAX,
          message = error.message,
          start = range.first,
          end = range.last + 1,
      )
    }
  }

  private fun parse(context: XmlDiagnosticContext): List<ParserError> {
    val errors = mutableListOf<ParserError>()
    val configuration = XIncludeAwareParserConfiguration()
    configureSafely(configuration, FEATURE_VALIDATION, false)
    configureSafely(configuration, FEATURE_SCHEMA_VALIDATION, false)
    configureSafely(configuration, FEATURE_LOAD_EXTERNAL_DTD, false)
    configureSafely(configuration, FEATURE_EXTERNAL_GENERAL_ENTITIES, false)
    configureSafely(configuration, FEATURE_EXTERNAL_PARAMETER_ENTITIES, false)
    configureSafely(configuration, FEATURE_XINCLUDE, false)
    configureSafely(configuration, FEATURE_DISALLOW_DOCTYPE, true)
    configureSafely(configuration, FEATURE_CONTINUE_AFTER_FATAL_ERROR, true)
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

    val input = XMLInputSource(null, context.file.toUri().toString(), null)
    input.characterStream = StringReader(context.text)
    try {
      configuration.parse(input)
    } catch (_: XNIException) {
      // Fatal well-formedness errors are already delivered to the error handler.
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
            message = exception.message ?: "Invalid XML syntax",
            characterOffset = exception.characterOffset,
            line = exception.lineNumber,
            column = exception.columnNumber,
        )
  }

  private fun errorOffset(error: ParserError, text: String): Int {
    if (error.characterOffset > 0) {
      return (error.characterOffset - 1).coerceIn(0, text.length)
    }
    if (error.line <= 0 || error.column <= 0) {
      return 0
    }
    var line = 1
    var offset = 0
    while (offset < text.length && line < error.line) {
      if (text[offset++] == '\n') {
        line++
      }
    }
    return (offset + error.column - 1).coerceIn(0, text.length)
  }

  private fun parserErrorRange(key: String, offset: Int, text: String): IntRange {
    if (text.isEmpty()) {
      return 0..0
    }
    val safeOffset = offset.coerceIn(0, text.lastIndex)
    return when (key) {
      "EqRequiredInAttribute", "OpenQuoteExpected", "AttributeNotUnique" ->
          selectNameAround(safeOffset, text)
      "LessthanInAttValue" -> safeOffset..safeOffset
      "DashDashInComment" ->
          (safeOffset - 1).coerceAtLeast(0)..(safeOffset + 1).coerceAtMost(text.lastIndex)
      else -> safeOffset..safeOffset
    }
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

  private fun isXmlNameCharacter(character: Char): Boolean {
    return character.isLetterOrDigit() || character == '_' || character == '-' || character == ':' || character == '.'
  }

  private fun configureSafely(
      configuration: XIncludeAwareParserConfiguration,
      feature: String,
      enabled: Boolean,
  ) {
    try {
      configuration.setFeature(feature, enabled)
    } catch (_: XNIException) {
      // Xerces variants may not expose every optional feature. Core parsing remains available.
    }
  }

  private data class ParserError(
      val key: String,
      val message: String,
      val characterOffset: Int,
      val line: Int,
      val column: Int,
  )

  private const val CODE_XML_PARSER_SYNTAX = "XML005"
  private const val MAX_PARSER_ERRORS = 20
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

  private val ERRORS_COVERED_BY_TOLERANT_DOM =
      setOf(
          "AttributeNotUnique",
          "AttributePrefixUnbound",
          "ElementUnterminated",
          "ETagRequired",
          "ETagUnterminated",
          "MarkupEntityMismatch",
          "PrematureEOF",
      )
}