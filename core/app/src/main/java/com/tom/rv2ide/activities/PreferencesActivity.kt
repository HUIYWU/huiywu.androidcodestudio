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
package com.tom.rv2ide.activities

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.core.graphics.Insets
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.tom.rv2ide.R
import com.tom.rv2ide.app.EdgeToEdgeIDEActivity
import com.tom.rv2ide.databinding.ActivityPreferencesBinding
import com.tom.rv2ide.fragments.IDEPreferencesFragment
import com.tom.rv2ide.preferences.IDEPreferences as prefs
import com.tom.rv2ide.preferences.addRootPreferences
import com.tom.rv2ide.preferences.internal.EditorPreferences
import com.tom.rv2ide.utils.Environment
import java.io.File
import kotlin.system.exitProcess

class PreferencesActivity : EdgeToEdgeIDEActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

  private var _binding: ActivityPreferencesBinding? = null
  private val binding: ActivityPreferencesBinding
    get() = checkNotNull(_binding) { "Activity has been destroyed" }

  private val rootFragment by lazy { IDEPreferencesFragment() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setSupportActionBar(binding.toolbar)
    supportActionBar!!.setTitle(R.string.ide_preferences)
    supportActionBar!!.setDisplayHomeAsUpEnabled(true)

    binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

    if (savedInstanceState != null) {
      return
    }

    (prefs.children as MutableList?)?.clear()

    prefs.addRootPreferences()

    val args = Bundle()
    args.putParcelableArrayList(IDEPreferencesFragment.EXTRA_CHILDREN, ArrayList(prefs.children))

    rootFragment.arguments = args
    loadFragment(rootFragment)
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)

    if (requestCode == 1001 && resultCode == Activity.RESULT_OK) {
      data?.data?.let { uri -> importEditorFont(uri) }
    }
  }

  private fun importEditorFont(uri: Uri) {
    try {
      val fileName = File(getFileName(uri) ?: "custom_font.ttf").name
      val extension = fileName.substringAfterLast('.', "").lowercase()
      if (extension !in setOf("ttf", "otf")) {
        Toast.makeText(this, "Only TTF and OTF fonts can be imported.", Toast.LENGTH_LONG).show()
        return
      }

      val fontDir = File("${Environment.HOME}/.androidide/ui")
      if (!fontDir.exists()) {
        fontDir.mkdirs()
      }

      val destFile = File(fontDir, fileName)
      val tempFile = File(fontDir, "$fileName.importing")
      contentResolver.openInputStream(uri)?.use { input ->
        tempFile.outputStream().use { output -> input.copyTo(output) }
      } ?: error("Unable to open selected font.")

      runCatching { Typeface.createFromFile(tempFile) }.getOrElse {
        tempFile.delete()
        throw IllegalArgumentException("The selected file is not a valid font.", it)
      }

      if (destFile.exists() && !destFile.delete()) {
        tempFile.delete()
        error("Unable to replace existing font.")
      }
      if (!tempFile.renameTo(destFile)) {
        tempFile.delete()
        error("Unable to save imported font.")
      }

      EditorPreferences.selectedCustomFont = fileName
      Toast.makeText(this, "Font imported and applied: $fileName", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
      Toast.makeText(this, "Error importing font: ${e.message}", Toast.LENGTH_LONG).show()
    }
  }
  
  private fun getFileName(uri: Uri): String? {
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

  /** Force restart the entire application Call this method when theme changes need to be applied */
  fun forceRestartApp() {
    finishAffinity() // Close all activities

    // Restart the application
    val intent = packageManager.getLaunchIntentForPackage(packageName)
    intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
    intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)

    // Force exit to ensure clean restart
    exitProcess(0)
  }

  override fun onApplySystemBarInsets(insets: Insets) {
    if (_binding == null) return // Skip if binding not initialized yet

    val toolbar: View = binding.toolbar
    toolbar.setPadding(
        toolbar.paddingLeft + insets.left,
        toolbar.paddingTop,
        toolbar.paddingRight + insets.right,
        toolbar.paddingBottom,
    )

    val fragmentContainer: View = binding.fragmentContainer
    fragmentContainer.setPadding(
        fragmentContainer.paddingLeft + insets.left,
        fragmentContainer.paddingTop,
        fragmentContainer.paddingRight + insets.right,
        fragmentContainer.paddingBottom,
    )
  }

  override fun bindLayout(): View {
    _binding = ActivityPreferencesBinding.inflate(layoutInflater)
    return binding.root
  }

  private fun loadFragment(fragment: Fragment) {
    supportFragmentManager.beginTransaction()
        .setReorderingAllowed(true)
        .replace(binding.fragmentContainer.id, fragment)
        .commit()
  }

  override fun onPreferenceStartFragment(
      caller: PreferenceFragmentCompat,
      pref: Preference,
  ): Boolean {
    val fragment =
        supportFragmentManager.fragmentFactory.instantiate(classLoader, checkNotNull(pref.fragment))
            .apply {
              arguments = pref.extras
              setTargetFragment(caller, 0)
            }

    supportFragmentManager.beginTransaction()
        .setReorderingAllowed(true)
        .replace(binding.fragmentContainer.id, fragment)
        .addToBackStack(pref.key)
        .commit()

    return true
  }

  override fun onDestroy() {
    super.onDestroy()
    _binding = null
  }
}
