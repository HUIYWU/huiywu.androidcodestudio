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
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View

/** Temporary logcat instrumentation for dialog window animation investigations. */
object DialogAnimationDiagnostics {
  private const val TAG = "DialogAnimationDiag"

  fun beforeShow(dialog: Dialog, label: String): Long {
    val startedAt = SystemClock.uptimeMillis()
    log(dialog, label, "beforeShow", startedAt)
    return startedAt
  }

  fun afterShow(dialog: Dialog, label: String, startedAt: Long) {
    log(dialog, label, "afterShow", startedAt)
    val decor = dialog.window?.decorView ?: return
    decor.post { log(dialog, label, "decorPost", startedAt) }
    decor.postOnAnimation { log(dialog, label, "firstFrame", startedAt) }
    decor.postOnAnimation { decor.postOnAnimation { log(dialog, label, "secondFrame", startedAt) } }
  }

  fun dismissed(dialog: Dialog, label: String, startedAt: Long) {
    log(dialog, label, "dismissed", startedAt)
  }

  private fun log(dialog: Dialog, label: String, event: String, startedAt: Long) {
    val window = dialog.window
    val decor: View? = window?.decorView
    val elapsed = SystemClock.uptimeMillis() - startedAt
    val animatorScale = runCatching {
      Settings.Global.getFloat(dialog.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
    }.getOrNull()
    val transitionScale = runCatching {
      Settings.Global.getFloat(dialog.context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE)
    }.getOrNull()
    Log.d(
        TAG,
        "$label event=$event elapsedMs=$elapsed showing=${dialog.isShowing} " +
            "windowAnimations=${window?.attributes?.windowAnimations} " +
            "size=${decor?.width}x${decor?.height} alpha=${decor?.alpha} " +
            "scale=${decor?.scaleX},${decor?.scaleY} focused=${decor?.hasFocus()} " +
            "animatorScale=$animatorScale transitionScale=$transitionScale",
    )
  }
}
