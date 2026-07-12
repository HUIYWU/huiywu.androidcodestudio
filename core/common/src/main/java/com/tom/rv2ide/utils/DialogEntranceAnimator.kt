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
import androidx.core.view.doOnPreDraw

/**
 * A deterministic dialog entrance used where device window animations are skipped intermittently.
 */
object DialogEntranceAnimator {
  private const val DURATION_MS = 150L
  private const val START_ALPHA = 0.4f
  private const val START_SCALE = 0.8f

  fun prepare(dialog: Dialog) {
    val decor = dialog.window?.decorView ?: return
    dialog.window?.setWindowAnimations(0)
    decor.alpha = START_ALPHA
    decor.scaleX = START_SCALE
    decor.scaleY = START_SCALE
  }

  fun start(dialog: Dialog) {
    val decor = dialog.window?.decorView ?: return
    decor.doOnPreDraw {
      decor.animate()
          .alpha(1f)
          .scaleX(1f)
          .scaleY(1f)
          .setDuration(DURATION_MS)
          .start()
    }
  }
}
