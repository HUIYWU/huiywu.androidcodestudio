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
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowInsets

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
    intArrayOf(16, 32, 64, 100, 150, 200).forEach { delayMs ->
      decor.postDelayed({ log(dialog, label, "sample${delayMs}ms", startedAt) }, delayMs.toLong())
    }
  }

  fun dismissed(dialog: Dialog, label: String, startedAt: Long) {
    log(dialog, label, "dismissed", startedAt)
  }

  fun event(dialog: Dialog, label: String, event: String, startedAt: Long) {
    log(dialog, label, event, startedAt)
  }

  private fun log(dialog: Dialog, label: String, event: String, startedAt: Long) {
    val window = dialog.window
    val decor: View? = window?.decorView
    val focusedView = decor?.findFocus()
    val imeVisible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      decor?.rootWindowInsets?.isVisible(WindowInsets.Type.ime())
    } else {
      null
    }
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
            "softInputMode=${window?.attributes?.softInputMode} dimAmount=${window?.attributes?.dimAmount} " +
            "size=${decor?.width}x${decor?.height} laidOut=${decor?.isLaidOut} " +
            "visibility=${decor?.visibility} alpha=${decor?.alpha} scale=${decor?.scaleX},${decor?.scaleY} " +
            "focused=${decor?.hasFocus()} focusView=${focusedView?.javaClass?.simpleName} " +
            "textEditor=${focusedView?.onCheckIsTextEditor()} imeVisible=$imeVisible " +
            "flags=${window?.attributes?.flags} type=${window?.attributes?.type} " +
            "gravity=${window?.attributes?.gravity} format=${window?.attributes?.format} " +
            "animatorScale=$animatorScale transitionScale=$transitionScale", 
    )
  }
}
