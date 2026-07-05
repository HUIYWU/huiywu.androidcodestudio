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

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.preferences.internal.EditorPreferences
import java.io.File

object EditorFontImporter {

  private const val FALLBACK_FILE_NAME = "custom_font.ttf"
  private val SUPPORTED_EXTENSIONS = setOf("ttf", "otf")

  sealed class Result {
    data class Success(val fileName: String) : Result()

    data class Error(
        @StringRes val messageRes: Int,
        val formatArg: String? = null,
    ) : Result()
  }

  fun createPickerIntent(context: Context): Intent {
    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
      type = "*/*"
      addCategory(Intent.CATEGORY_OPENABLE)
      putExtra(
          Intent.EXTRA_MIME_TYPES,
          arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf"),
      )
    }

    return Intent.createChooser(intent, context.getString(R.string.idepref_customFont_picker_title))
  }

  fun importFont(contentResolver: ContentResolver, uri: Uri): Result {
    return try {
      val fileName = File(getFileName(contentResolver, uri) ?: FALLBACK_FILE_NAME).name
      val extension = fileName.substringAfterLast('.', "").lowercase()
      if (extension !in SUPPORTED_EXTENSIONS) {
        return Result.Error(R.string.idepref_customFont_invalid_extension)
      }

      val fontDir = File("${Environment.HOME}/.androidide/ui")
      if (!fontDir.exists()) {
        fontDir.mkdirs()
      }

      val destFile = File(fontDir, fileName)
      val tempFile = File(fontDir, "$fileName.importing")
      contentResolver.openInputStream(uri)?.use { input ->
        tempFile.outputStream().use { output -> input.copyTo(output) }
      } ?: return Result.Error(R.string.idepref_customFont_open_failed)

      val isValidFont = runCatching { Typeface.createFromFile(tempFile) }.isSuccess
      if (!isValidFont) {
        tempFile.delete()
        return Result.Error(R.string.idepref_customFont_invalid_file)
      }

      if (destFile.exists() && !destFile.delete()) {
        tempFile.delete()
        return Result.Error(R.string.idepref_customFont_replace_failed)
      }
      if (!tempFile.renameTo(destFile)) {
        tempFile.delete()
        return Result.Error(R.string.idepref_customFont_save_failed)
      }

      EditorPreferences.selectedCustomFont = fileName
      Result.Success(fileName)
    } catch (e: Exception) {
      Result.Error(R.string.idepref_customFont_import_error, e.message)
    }
  }

  private fun getFileName(contentResolver: ContentResolver, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
      contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (columnIndex != -1) {
            result = cursor.getString(columnIndex)
          }
        }
      }
    }
    if (result == null) {
      result = uri.path?.let { path ->
        val cut = path.lastIndexOf('/')
        if (cut != -1) path.substring(cut + 1) else path
      }
    }
    return result
  }
}
