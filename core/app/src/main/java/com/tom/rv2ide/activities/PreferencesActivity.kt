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
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.tom.rv2ide.utils.EditorFontImporter
import kotlin.system.exitProcess

class PreferencesActivity : EdgeToEdgeIDEActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback, FontImportLauncherHost {

  private val fontPickerLauncher =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
          result.data?.data?.let { uri ->
            when (val importResult = EditorFontImporter.importFont(contentResolver, uri)) {
              is EditorFontImporter.Result.Success -> {
                Toast.makeText(
                    this,
                    getString(R.string.idepref_customFont_imported_applied, importResult.fileName),
                    Toast.LENGTH_SHORT,
                ).show()
              }
              is EditorFontImporter.Result.Error -> {
                val message =
                    importResult.formatArg?.let { getString(importResult.messageRes, it) }
                        ?: getString(importResult.messageRes)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
              }
            }
          }
        }
      }

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

  override fun launchFontPicker(intent: Intent) {
    fontPickerLauncher.launch(intent)
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
