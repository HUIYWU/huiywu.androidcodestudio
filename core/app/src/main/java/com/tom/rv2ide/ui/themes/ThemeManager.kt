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
      // No need to apply Material You theme
      return
    }

    val style =
        if (isDarkModeResolved(activity)) {
          theme.styleDark
        } else {
          theme.styleLight
        }

    activity.setTheme(style)
  }

  /**
   * Resolve dark mode from the user's selected UI mode preference instead of the system
   * configuration. The system configuration (`AppCompatDelegate`) is only applied after
   * `Application.onCreate`, which is too late for the first frame of a cold start / restart.
   * Reading the preference directly keeps every early Activity frame consistent with the user's
   * choice (fixes the blank white flash when the IDE UI mode differs from the system mode).
   */
  private fun isDarkModeResolved(activity: Activity): Boolean {
    return when (GeneralPreferences.uiMode) {
      AppCompatDelegate.MODE_NIGHT_YES -> true
      AppCompatDelegate.MODE_NIGHT_NO -> false
      else -> activity.isSystemInDarkMode()
    }
  }

  /** Get the currently selected theme. */
  override fun getCurrentTheme(): IDETheme {
    return GeneralPreferences.selectedTheme?.let { IDETheme.valueOf(it) } ?: IDETheme.DEFAULT
  }
}
