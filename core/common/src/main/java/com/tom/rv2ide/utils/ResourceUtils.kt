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

package com.tom.rv2ide.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources.Theme
import android.util.TypedValue
import androidx.appcompat.app.AppCompatDelegate
import com.tom.rv2ide.preferences.internal.GeneralPreferences

fun Context.isSystemInDarkMode(): Boolean {
  return this.resources.configuration.isSystemInDarkMode()
}

fun Configuration.isSystemInDarkMode(): Boolean {
  return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

/**
 * Resolve the effective dark mode from the user's UI-mode preference, falling back to the system
 * configuration only when the preference is [AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM].
 *
 * This is the single source of truth for dark-mode decisions across window theming, editor color
 * schemes and dynamic colors. Reading the preference directly (instead of the system configuration)
 * keeps every frame consistent with the user's choice even before `AppCompatDelegate` has applied
 * the night mode on a cold start / restart.
 */
fun Context.isDarkModeResolved(): Boolean {
  return when (GeneralPreferences.uiMode) {
    AppCompatDelegate.MODE_NIGHT_YES -> true
    AppCompatDelegate.MODE_NIGHT_NO -> false
    else -> isSystemInDarkMode()
  }
}

@JvmOverloads
fun Context.resolveAttr(id: Int, resolveRefs: Boolean = true): Int {
  return theme.resolveAttr(id, resolveRefs)
}

@JvmOverloads
fun Theme.resolveAttr(id: Int, resolveRefs: Boolean = true): Int =
    TypedValue().let {
      resolveAttribute(id, it, resolveRefs)
      it.data
    }
