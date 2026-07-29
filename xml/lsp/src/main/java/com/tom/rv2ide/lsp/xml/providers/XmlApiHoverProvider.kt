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
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.xml.providers

import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.lsp.xml.resolver.StyleableResolver
import com.tom.rv2ide.xml.versions.ApiVersions
import com.tom.rv2ide.xml.widgets.WidgetTable

/** Provides non-diagnostic "Since API" information for framework layout tags and attributes. */
internal class XmlApiHoverProvider {

  fun hover(text: String, offset: Int): String? {
    val versions = Lookup.getDefault().lookup(ApiVersions.COMPLETION_LOOKUP_KEY) ?: return null

    symbolAt(ANDROID_ATTRIBUTE, text, offset)?.let { attributeName ->
      val since = versions.memberInfo(ANDROID_R_ATTR_CLASS, attributeName.removePrefix(ANDROID_PREFIX))?.since
      return since?.let { format(attributeName, it) }
    }

    val tagName = tagNameAt(text, offset) ?: return null
    val widgets = Lookup.getDefault().lookup(WidgetTable.COMPLETION_LOOKUP_KEY) ?: return null
    val widget = StyleableResolver.widgetFor(tagName, widgets) ?: return null
    val since = versions.classInfo(widget.qualifiedName)?.since ?: return null
    return format(tagName, since)
  }

  /**
   * Uses source ranges rather than LemMinX's recovered-node range because hover requests commonly
   * land on a tag or attribute name, which may not be returned by findNodeAt/findAttrAt.
   */
  private fun symbolAt(pattern: Regex, text: String, offset: Int): String? {
    return pattern.findAll(text).firstOrNull { match -> offset in match.range }?.value
  }

  private fun tagNameAt(text: String, offset: Int): String? {
    return TAG_NAME.findAll(text)
        .firstOrNull { match -> offset in (match.range.first + 1)..match.range.last }
        ?.groupValues
        ?.get(1)
  }

  internal fun format(symbol: String, sinceApi: Int): String =
      "```xml\n$symbol\n```\n- - -\n\nSince API: `$sinceApi`"

  private companion object {
    const val ANDROID_PREFIX = "android:"
    const val ANDROID_R_ATTR_CLASS = "android.R\$attr"
    val ANDROID_ATTRIBUTE = Regex("\\bandroid:[A-Za-z_][A-Za-z0-9_]*")
    val TAG_NAME = Regex("<([A-Za-z_][A-Za-z0-9_.]*)")
  }
}
