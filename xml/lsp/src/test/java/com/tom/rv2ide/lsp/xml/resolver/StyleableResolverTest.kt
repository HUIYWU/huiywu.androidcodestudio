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
package com.tom.rv2ide.lsp.xml.resolver

import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.lsp.xml.diagnostics.rules.LayoutDiagnosticRule
import com.tom.rv2ide.xml.widgets.Widget
import com.tom.rv2ide.xml.widgets.WidgetTable
import com.tom.rv2ide.xml.widgets.WidgetType
import junit.framework.TestCase

class StyleableResolverTest : TestCase() {

  fun testUsesSimpleLookupForSimpleTagAndQualifiedLookupForQualifiedTag() {
    val textView = testWidget("android.widget.TextView")
    val customView = testWidget("example.CustomView")
    val table = RecordingWidgetTable(textView, customView)

    assertThat(StyleableResolver.widgetFor("TextView", table)).isSameInstanceAs(textView)
    assertThat(table.simpleLookups).containsExactly("TextView")
    assertThat(table.qualifiedLookups).isEmpty()

    assertThat(StyleableResolver.widgetFor("example.CustomView", table)).isSameInstanceAs(customView)
    assertThat(table.qualifiedLookups).containsExactly("example.CustomView")
  }
  fun testIncompleteTagNameDoesNotQueryWidgetTable() {
    val table = RecordingWidgetTable(testWidget("android.widget.TextView"))

    assertThat(StyleableResolver.widgetFor(null, table)).isNull()
    assertThat(StyleableResolver.widgetFor("", table)).isNull()
    assertThat(table.simpleLookups).isEmpty()
    assertThat(table.qualifiedLookups).isEmpty()
  }

  fun testNormalizesQualifiedStyleableClassNames() {
    assertThat(StyleableResolver.simpleName("androidx.coordinatorlayout.widget.CoordinatorLayout"))
        .isEqualTo("CoordinatorLayout")
    assertThat(StyleableResolver.simpleName("FrameLayout")).isEqualTo("FrameLayout")
  }

  fun testCustomAttributeRequiresAbsenceFromStyleableAndGlobalAttrs() {
    assertThat(
            LayoutDiagnosticRule.isUnknownCustomAttribute(
                "knownInStyleable",
                setOf("knownInStyleable"),
            ) { false }
        )
        .isFalse()
    assertThat(
            LayoutDiagnosticRule.isUnknownCustomAttribute(
                "knownGlobally",
                emptySet(),
            ) { it == "knownGlobally" }
        )
        .isFalse()
    assertThat(
            LayoutDiagnosticRule.isUnknownCustomAttribute(
                "knownInStyleabl",
                setOf("knownInStyleable"),
            ) { false }
        )
        .isTrue()
    assertThat(
            LayoutDiagnosticRule.isUnknownCustomAttribute(
                "bindingAdapterAttribute",
                setOf("knownInStyleable"),
            ) { false }
        )
        .isFalse()
  }

  private fun testWidget(qualifiedName: String): Widget {
    return object : Widget {
      override val simpleName = qualifiedName.substringAfterLast('.')
      override val qualifiedName = qualifiedName
      override val type = WidgetType.WIDGET
      override val superclasses = emptyList<String>()
    }
  }

  private class RecordingWidgetTable(vararg widgets: Widget) : WidgetTable {
    private val byQualifiedName = widgets.associateBy(Widget::qualifiedName)
    private val bySimpleName = widgets.associateBy(Widget::simpleName)
    val qualifiedLookups = mutableListOf<String>()
    val simpleLookups = mutableListOf<String>()

    override fun getWidget(name: String): Widget? {
      qualifiedLookups.add(name)
      return byQualifiedName[name]
    }

    override fun findWidgetWithSimpleName(name: String): Widget? {
      simpleLookups.add(name)
      return bySimpleName[name]
    }

    override fun getAllWidgets(): Set<Widget> = byQualifiedName.values.toSet()
  }
}