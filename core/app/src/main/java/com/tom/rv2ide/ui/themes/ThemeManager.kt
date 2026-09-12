/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ui.themes

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.google.auto.service.AutoService
import com.tom.rv2ide.preferences.internal.GeneralPreferences
import com.tom.rv2ide.utils.isSystemInDarkMode

/**
 * Theme manager for AndroidIDE.
 *
 * @author Akash Yadav
 */
@Suppress("unused")
@AutoService(IThemeManager::class)
class ThemeManager : IThemeManager {

  /**
   * Apply the current theme to the given activity. Does nothing if theme is set to
   * [Material You][IDETheme.MATERIAL_YOU].
   */
  override fun applyTheme(activity: Activity) {

    val theme = getCurrentTheme()
    if (theme == IDETheme.MATERIAL_YOU) {
      // Material You colors are applied per-activity so that switching away from (or to) this
      // theme takes effect on Activity.recreate() without a cold process restart.
      DynamicColors.applyToActivityIfAvailable(activity)
      return
    }

    val style = if (isDarkModeResolved(activity)) theme.styleDark else theme.styleLight

    activity.setTheme(style)
  }

  /**
   * Resolve dark mode from the user's selected UI-mode preference, falling back to the system
   * configuration only when the preference is MODE_NIGHT_FOLLOW_SYSTEM. Reading the preference
   * directly keeps early Activity frames consistent with the user's choice.
   */
  private fun isDarkModeResolved(activity: Activity): Boolean {
    val uiMode = GeneralPreferences.uiMode
    if (uiMode == AppCompatDelegate.MODE_NIGHT_YES) {
      return true
    }
    if (uiMode == AppCompatDelegate.MODE_NIGHT_NO) {
      return false
    }
    return activity.isSystemInDarkMode()
  }

  /** Get the currently selected theme. */
  override fun getCurrentTheme(): IDETheme {
    return GeneralPreferences.selectedTheme?.let { IDETheme.valueOf(it) } ?: IDETheme.DEFAULT
  }

  /**
   * Derives the signature from the same two inputs that [applyTheme] uses: the selected [IDETheme]
   * and the resolved dark mode. For [IDETheme.MATERIAL_YOU] the palette is supplied by the platform,
   * so the theme name alone is not enough and the resolved mode is included for every theme.
   */
  override fun getAppliedThemeSignature(activity: Activity): String {
    val mode = if (isDarkModeResolved(activity)) "dark" else "light"
    return "${getCurrentTheme().name}:$mode"
  }
}
