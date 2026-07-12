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

import android.app.Dialog
import android.view.WindowManager

/**
 * Temporarily keeps a dialog out of IME targeting while its native entrance animation runs.
 */
object DialogImeEntranceGuard {
  private const val RELEASE_DELAY_MS = 180L

  fun prepare(dialog: Dialog) {
    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
  }

  fun releaseAfterEntrance(dialog: Dialog, label: String, startedAt: Long) {
    val decor = dialog.window?.decorView ?: return
    decor.postDelayed({
      if (dialog.isShowing) {
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        DialogAnimationDiagnostics.event(dialog, label, "imeEntranceGuardReleased", startedAt)
      }
    }, RELEASE_DELAY_MS)
  }
}