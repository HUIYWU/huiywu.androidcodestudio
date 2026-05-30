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
package com.tom.rv2ide.lsp.kotlin

import android.content.Context
import com.tom.rv2ide.preferences.internal.LSPPreferences

/**
 * Minimal factory for creating the active Kotlin LSP backend connection.
 *
 * The current default remains [KotlinServerProcessManager] (org.javacs.kt-based),
 * but the creation decision now lives behind a dedicated seam so future Kotlin
 * backends can be introduced without changing [KotlinLanguageServer].
 *
 * Available concrete backends currently include:
 * - [KotlinLspBackendId.JAVACS] for the bundled org.javacs.kt server
 * - [KotlinLspBackendId.FWCD] for a future fwcd/kotlin-language-server bundle
 * - [KotlinLspBackendId.STUB] for a no-op structural placeholder
 */
object KotlinLspBackendFactory {
  private fun activeBackendId(): KotlinLspBackendId {
    return when (LSPPreferences.kotlinLspBackend.trim().lowercase()) {
      LSPPreferences.KOTLIN_LSP_BACKEND_JAVACS -> KotlinLspBackendId.JAVACS
      LSPPreferences.KOTLIN_LSP_BACKEND_FWCD -> KotlinLspBackendId.FWCD
      LSPPreferences.KOTLIN_LSP_BACKEND_STUB -> KotlinLspBackendId.STUB
      else -> KotlinLspBackendId.JAVACS
    }
  }

  @Suppress("UNUSED_PARAMETER")
  fun createSpec(context: Context): KotlinLspBackendSpec {
    return when (activeBackendId()) {

      KotlinLspBackendId.JAVACS ->
          KotlinLspBackendSpec(
              id = KotlinLspBackendId.JAVACS,
              connection = KotlinServerProcessManager(),
              configurator = JavacsKotlinLspBackendConfigurator,
          )
      KotlinLspBackendId.FWCD ->
          KotlinLspBackendSpec(
              id = KotlinLspBackendId.FWCD,
              connection = FwcdKotlinLspConnection(),
              configurator = FwcdKotlinLspBackendConfigurator,
          )
      KotlinLspBackendId.STUB ->
          KotlinLspBackendSpec(
              id = KotlinLspBackendId.STUB,
              connection = StubKotlinLspConnection(context),
              configurator = StubKotlinLspBackendConfigurator,
          )
    }
  }
}
