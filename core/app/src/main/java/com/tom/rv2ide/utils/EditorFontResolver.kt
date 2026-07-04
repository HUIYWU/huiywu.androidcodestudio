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

package com.tom.rv2ide.utils

import android.content.Context
import android.graphics.Typeface
import java.io.File

object EditorFontResolver {

  const val DEFAULT_JB_MONO_SENTINEL = "__default_jb_mono__"
  private const val DEFAULT_JB_MONO_FILE_NAME = "jetbrains-mono.ttf"
  private const val DEFAULT_JB_MONO_ASSET_PATH = "fonts/jetbrains-mono.ttf"

  fun cacheKey(selectedFont: String?): String {
    return selectedFont ?: DEFAULT_JB_MONO_SENTINEL
  }

  fun resolve(context: Context, selectedFont: String?): Typeface {
    val defaultStorageFile = File("${Environment.HOME}/.androidide/ui/$DEFAULT_JB_MONO_FILE_NAME")

    fun loadFromFile(file: File): Typeface? {
      return if (file.isFile && file.canRead()) {
        runCatching { Typeface.createFromFile(file) }.getOrNull()
      } else {
        null
      }
    }

    fun loadDefaultTypeface(): Typeface {
      return loadFromFile(defaultStorageFile)
          ?: runCatching { Typeface.createFromAsset(context.assets, DEFAULT_JB_MONO_ASSET_PATH) }.getOrNull()
          ?: Typeface.MONOSPACE
    }

    if (selectedFont.isNullOrBlank()) {
      return loadDefaultTypeface()
    }

    val customFile = File("${Environment.HOME}/.androidide/ui/$selectedFont")
    return loadFromFile(customFile) ?: loadDefaultTypeface()
  }
}
