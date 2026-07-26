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
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 */
package com.tom.rv2ide.lsp.xml.diagnostics

import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.lsp.xml.diagnostics.rules.LayoutDiagnosticRule
import com.tom.rv2ide.xml.versions.ApiVersion
import com.tom.rv2ide.xml.versions.ApiVersions
import junit.framework.TestCase

class LayoutApiLevelDiagnosticTest : TestCase() {

  fun testUsesWidgetClassAndAndroidRAttrApiMetadata() {
    val queries = mutableListOf<String>()
    val versions =
        object : ApiVersions {
          override fun classInfo(name: String): ApiVersion? {
            queries.add("class:$name")
            return ApiVersion(31)
          }

          override fun memberInfo(className: String, identifier: String): ApiVersion? {
            queries.add("member:$className#$identifier")
            return ApiVersion(29)
          }
        }

    assertThat(LayoutDiagnosticRule.requiredApiForWidget(versions, "android.widget.Magnifier"))
        .isEqualTo(31)
    assertThat(LayoutDiagnosticRule.requiredApiForFrameworkAttribute(versions, "forceDarkAllowed"))
        .isEqualTo(29)
    assertThat(queries)
        .containsExactly(
            "class:android.widget.Magnifier",
            "member:android.R\$attr#forceDarkAllowed",
        )
        .inOrder()
  }

  fun testEffectiveApiUsesMinSdkForBaseResources() {
    assertThat(LayoutDiagnosticRule.effectiveApiLevel(minSdk = 21, resourceApiQualifier = 0))
        .isEqualTo(21)
  }

  fun testVersionedResourceDirectoryRaisesEffectiveApi() {
    assertThat(LayoutDiagnosticRule.effectiveApiLevel(minSdk = 21, resourceApiQualifier = 31))
        .isEqualTo(31)
  }

  fun testMinSdkWinsWhenHigherThanResourceQualifier() {
    assertThat(LayoutDiagnosticRule.effectiveApiLevel(minSdk = 35, resourceApiQualifier = 31))
        .isEqualTo(35)
  }

  fun testReportsOnlyKnownApiStrictlyAboveEffectiveMinimum() {
    assertThat(LayoutDiagnosticRule.requiresHigherApi(requiredApi = 31, effectiveApi = 21)).isTrue()
    assertThat(LayoutDiagnosticRule.requiresHigherApi(requiredApi = 31, effectiveApi = 31)).isFalse()
    assertThat(LayoutDiagnosticRule.requiresHigherApi(requiredApi = 21, effectiveApi = 31)).isFalse()
    assertThat(LayoutDiagnosticRule.requiresHigherApi(requiredApi = null, effectiveApi = 21)).isFalse()
  }
}