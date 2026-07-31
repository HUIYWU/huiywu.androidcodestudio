/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.diagnostics

import com.tom.rv2ide.lsp.models.DiagnosticItem
import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/** Machine-readable payloads attached to XML diagnostics through [DiagnosticItem.extra]. */
internal data class ClosingTagMismatchDiagnosticData(
  val actualName: String,
  val expectedName: String,
)

/**
 * XML LSP diagnostic messages. The key and argument order are the stable contract;
 * the returned string is only a localized presentation value.
 */
internal object XmlDiagnosticMessages {
  private const val BUNDLE_BASE_NAME =
    "com.tom.rv2ide.lsp.xml.diagnostics.messages.XmlDiagnostics"
  private const val CLOSING_TAG_MISMATCH_KEY = "xml.diagnostic.closingTagMismatch"

  fun closingTagMismatch(
    actualName: String,
    expectedName: String,
    locale: Locale = Locale.getDefault(),
  ): String = format(
    key = CLOSING_TAG_MISMATCH_KEY,
    arguments = arrayOf<Any>(actualName, expectedName),
    locale = locale,
  )

  private fun format(key: String, arguments: Array<Any>, locale: Locale): String {
    val bundle = try {
      ResourceBundle.getBundle(BUNDLE_BASE_NAME, locale, XmlDiagnosticMessages::class.java.classLoader)
    } catch (_: MissingResourceException) {
      return key
    }
    val pattern = try {
      bundle.getString(key)
    } catch (_: MissingResourceException) {
      return key
    }
    return MessageFormat(pattern, locale).format(arguments)
  }
}
