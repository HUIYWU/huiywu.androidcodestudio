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
import org.eclipse.lemminx.dom.DOMDocument
import org.eclipse.lemminx.dom.DOMElement

/** Provides non-diagnostic "Since API" information for framework layout tags and attributes. */
internal class XmlApiHoverProvider {

  fun hover(document: DOMDocument, text: String, offset: Int): String? {
    val versions = Lookup.getDefault().lookup(ApiVersions.COMPLETION_LOOKUP_KEY) ?: return null
    val attribute = document.findAttrAt(offset)
    if (attribute != null) {
      val name = attribute.name
      val nameStart = name?.let { text.indexOf(it, attribute.start).takeIf { start -> start >= attribute.start } }
      if (name != null && nameStart != null &&
          offset in nameStart until (nameStart + name.length) && name.startsWith(ANDROID_PREFIX)) {
        val since = versions.memberInfo(ANDROID_R_ATTR_CLASS, name.removePrefix(ANDROID_PREFIX))?.since
        return since?.let { format(name, it) }
      }
    }

    val element = document.findNodeAt(offset) as? DOMElement ?: return null
    val tagName = element.tagName ?: return null
    val tagStart = element.start + 1
    if (offset !in tagStart until (tagStart + tagName.length)) return null
    val widgets = Lookup.getDefault().lookup(WidgetTable.COMPLETION_LOOKUP_KEY) ?: return null
    val widget = StyleableResolver.widgetFor(tagName, widgets) ?: return null
    val since = versions.classInfo(widget.qualifiedName)?.since ?: return null
    return format(tagName, since)
  }

  internal fun format(symbol: String, sinceApi: Int): String =
      "```xml\n$symbol\n```\n- - -\n\nSince API: `$sinceApi`"

  private companion object {
    const val ANDROID_PREFIX = "android:"
    const val ANDROID_R_ATTR_CLASS = "android.R\$attr"
  }
}
