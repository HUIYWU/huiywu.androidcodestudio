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

package com.tom.rv2ide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import com.google.android.material.transition.MaterialSharedAxis
import com.tom.rv2ide.preferences.IPreference
import com.tom.rv2ide.preferences.IPreferenceGroup
import com.tom.rv2ide.preferences.IPreferenceScreen
import com.tom.rv2ide.preferences.QUICK_INPUT_CUSTOMIZE_REFRESH_RESULT
import com.tom.rv2ide.preferences.QUICK_INPUT_CUSTOMIZE_SCREEN_KEY
import com.tom.rv2ide.preferences.observers.LSPStateObserver
import com.tom.rv2ide.preferences.quickInputCustomizeChildren

class IDEPreferencesFragment : BasePreferenceFragment() {

  private var children: List<IPreference> = emptyList()
  private var screenKey: String? = null

  private val serverStateListener = {
    refreshPreferences()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true).apply {
        duration = 320
    }
    returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false).apply {
        duration = 320
    }
    exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true).apply {
        duration = 320
    }
    reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false).apply {
        duration = 320
    }
  }

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View {
    return super.onCreateView(inflater, container, savedInstanceState)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
  }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)

    if (context == null) {
      return
    }

    @Suppress("DEPRECATION")
    this.children = arguments?.getParcelableArrayList(EXTRA_CHILDREN) ?: emptyList()
    screenKey = arguments?.getString(EXTRA_SCREEN_KEY)
    if (screenKey == QUICK_INPUT_CUSTOMIZE_SCREEN_KEY) {
      this.children = quickInputCustomizeChildren()
      parentFragmentManager.setFragmentResultListener(
          QUICK_INPUT_CUSTOMIZE_REFRESH_RESULT,
          this,
      ) { _, _ ->
        this.children = quickInputCustomizeChildren()
        refreshPreferences()
      }
    }

    preferenceScreen.removeAll()
    addChildren(this.children, preferenceScreen)
  }

  override fun onResume() {
    super.onResume()
    LSPStateObserver.addListener(serverStateListener)
  }

  override fun onPause() {
    super.onPause()
    LSPStateObserver.removeListener(serverStateListener)
  }

  private fun refreshPreferences() {
    if (context == null) {
      return
    }

    preferenceScreen.removeAll()
    addChildren(this.children, preferenceScreen)
  }

  private fun addChildren(children: List<IPreference>, pref: PreferenceGroup) {
    for (child in children) {
      val preference = child.onCreateView(requireContext())
      if (child is IPreferenceScreen) {
        preference.fragment = IDEPreferencesFragment::class.java.name
        preference.extras.putString(EXTRA_SCREEN_KEY, child.key)
        preference.extras.putParcelableArrayList(EXTRA_CHILDREN, ArrayList(child.children))
        pref.addPreference(preference)
        continue
      }

      if (child is IPreferenceGroup) {
        pref.addPreference(preference as PreferenceCategory)
        addChildren(child.children, preference)
        continue
      }

      pref.addPreference(preference)
    }
  }

  companion object {
    const val EXTRA_CHILDREN = "ide.preferences.fragment.children"
    const val EXTRA_SCREEN_KEY = "ide.preferences.fragment.screenKey"
  }
}