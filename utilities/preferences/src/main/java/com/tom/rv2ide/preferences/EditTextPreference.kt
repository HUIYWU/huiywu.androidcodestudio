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

package com.tom.rv2ide.preferences

import android.view.LayoutInflater
import android.view.WindowManager
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.preferences.databinding.LayoutDialogTextInputBinding
import com.tom.rv2ide.utils.DialogAnimationDiagnostics
import com.tom.rv2ide.utils.DialogImeEntranceGuard
import com.tom.rv2ide.utils.DialogUtils

/**
 * A preference which shows an edittext
 *
 * @author Akash Yadav
 */
abstract class EditTextPreference : DialogPreference() {

  override fun onPreferenceClick(preference: Preference): Boolean {
    val builder = DialogUtils.newMaterialDialogBuilder(preference.context)
    builder.setTitle(dialogTitle)
    dialogMessage?.let(builder::setMessage)
    builder.setCancelable(dialogCancellable)
    onConfigureDialog(preference, builder)

    val dialog = builder.create()
    // Keep IME-driven layout changes out of the window animation's first frame.
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    DialogImeEntranceGuard.prepare(dialog)
    val animationStartedAt = DialogAnimationDiagnostics.beforeShow(dialog, "EditTextPreference:$key")
    dialog.setOnDismissListener {
      DialogAnimationDiagnostics.dismissed(dialog, "EditTextPreference:$key", animationStartedAt)
    }
    dialog.show()
    DialogImeEntranceGuard.releaseAfterEntrance(dialog, "EditTextPreference:$key", animationStartedAt)
    DialogAnimationDiagnostics.afterShow(dialog, "EditTextPreference:$key", animationStartedAt)
    return true
  }

  override fun onConfigureDialog(preference: Preference, dialog: MaterialAlertDialogBuilder) {
    super.onConfigureDialog(preference, dialog)
    val binding = LayoutDialogTextInputBinding.inflate(LayoutInflater.from(dialog.context))
    onConfigureTextInput(binding.name)
    dialog.setView(binding.root)
    dialog.setPositiveButton(android.R.string.ok) { iface, _ ->
      iface.dismiss()
      onPreferenceChanged(preference, binding.name.editText?.text?.toString()?.trim())
    }
    dialog.setNegativeButton(android.R.string.cancel) { iface, _ -> iface.dismiss() }
  }

  protected open fun onConfigureTextInput(input: TextInputLayout) {}
}
