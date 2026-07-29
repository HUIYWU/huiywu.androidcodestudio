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

import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.xml.versions.ApiVersion
import junit.framework.TestCase

class XmlApiHoverProviderTest : TestCase() {

  fun testFormatsFrameworkSymbolSinceApiMetadata() {
    val content = XmlApiHoverProvider().format("android:forceDarkAllowed", ApiVersion(29))

    assertThat(content)
        .isEqualTo("```xml\nandroid:forceDarkAllowed\n```\n- - -\n\nSince API: `29`")
  }

  fun testFormatsDeprecatedAndRemovedApiMetadata() {
    val content = XmlApiHoverProvider().format("android:legacyAttribute", ApiVersion(1, 23, 31))

    assertThat(content)
        .isEqualTo(
            "```xml\nandroid:legacyAttribute\n```\n- - -\n\n" +
                "Since API: `1`  \nDeprecated since API: `23`  \nRemoved in API: `31`"
        )
  }

  fun testFormatsLaterApiWidgetLifecycleMetadata() {
    val content = XmlApiHoverProvider().format("Space", ApiVersion(14, 28))

    assertThat(content)
        .isEqualTo(
            "```xml\nSpace\n```\n- - -\n\n" +
                "Since API: `14`  \nDeprecated since API: `28`"
        )
  }
}
