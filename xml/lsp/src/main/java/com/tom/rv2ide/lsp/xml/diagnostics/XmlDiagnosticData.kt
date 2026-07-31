/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package com.tom.rv2ide.lsp.xml.diagnostics

import com.tom.rv2ide.lsp.models.DiagnosticItem
import java.util.Locale

/** Machine-readable payloads attached to XML diagnostics through [DiagnosticItem.extra]. */
internal data class ClosingTagMismatchDiagnosticData(
    val actualName: String,
    val expectedName: String,
)

/** User-facing XML diagnostic messages. Code actions must use diagnostic data, never these strings. */
internal object XmlDiagnosticMessages {
  fun closingTagMismatch(actualName: String, expectedName: String, locale: Locale = Locale.getDefault()):
      String {
    return if (locale.language.equals(Locale.CHINESE.language, ignoreCase = true)) {
      "结束标签 '</$actualName>' 与开始标签 '<$expectedName>' 不匹配"
    } else {
      "Closing tag '</$actualName>' does not match opening tag '<$expectedName>'"
    }
  }

  fun fixClosingTag(locale: Locale = Locale.getDefault()): String {
    return if (locale.language.equals(Locale.CHINESE.language, ignoreCase = true)) {
      "修正结束标签"
    } else {
      "Fix closing tag"
    }
  }
}