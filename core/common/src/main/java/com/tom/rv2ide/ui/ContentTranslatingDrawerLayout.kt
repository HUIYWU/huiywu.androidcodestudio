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

package com.tom.rv2ide.ui

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

/**
 * A [DrawerLayout] that scales its content when navigation drawers are opened or closed.
 *
 * @author Akash Yadav
 */
class ContentTranslatingDrawerLayout : InterceptableDrawerLayout {

  constructor(context: Context) : super(context)

  constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

  constructor(
      context: Context,
      attrs: AttributeSet?,
      defStyleAttr: Int,
  ) : super(context, attrs, defStyleAttr)

  /**
   * The ID of the child view which will be translated when the navigation views are
   * expanded/collapsed.
   *
   * Set this value to `-1` to disable transition.
   */
  var childId: Int = -1

  /** The [TranslationBehavior] for the start navigation view. */
  var translationBehaviorStart: TranslationBehavior = TranslationBehavior.DEFAULT

  /** The [TranslationBehavior] for the end navigation view. */
  var translationBehaviorEnd: TranslationBehavior = TranslationBehavior.DEFAULT

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private var backDispatcher: OnBackInvokedDispatcher? = null

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private var predictiveBackInProgress = false

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private val backAnimationCallback =
      object : OnBackAnimationCallback {
        override fun onBackStarted(backEvent: BackEvent) {
          predictiveBackInProgress = true
          findDrawerWithGravityCompat()?.let { drawerView ->
            applyBackProgress(drawerView, 0f)
          }
        }

        override fun onBackProgressed(backEvent: BackEvent) {
          findDrawerWithGravityCompat()?.let { drawerView ->
            applyBackProgress(drawerView, backEvent.progress)
          }
        }

        override fun onBackCancelled() {
          predictiveBackInProgress = false
          findDrawerWithGravityCompat()?.let { drawerView ->
            resetBackTranslation(drawerView)
            applyContentTranslation(drawerView, 1f)
          }
        }

        override fun onBackInvoked() {
          findDrawerWithGravityCompat()?.let { drawerView ->
            if (predictiveBackInProgress) {
              // The predictive-back progress already moved the drawer visually to its final
              // position. Commit that position without starting a second close animation.
              closeDrawer(drawerView, false)
            } else {
              // A key/button back has no predictive progress and must retain DrawerLayout's
              // regular closing animation.
              closeDrawer(drawerView, true)
            }
          }
          predictiveBackInProgress = false
        }
      }

  private fun findDrawerWithGravityCompat(): View? {
    for (index in 0 until childCount) {
      val child = getChildAt(index)
      val gravity = (child.layoutParams as? LayoutParams)?.gravity ?: continue
      val absoluteGravity = GravityCompat.getAbsoluteGravity(gravity, layoutDirection)
      if ((absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.LEFT) {
        return child
      }
    }
    return null
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private fun applyBackProgress(drawerView: View, progress: Float) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val drawerWidth = drawerView.width.toFloat()
    drawerView.translationX = -drawerWidth * clampedProgress
    applyContentTranslation(drawerView, 1f - clampedProgress)
    invalidate()
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private fun resetBackTranslation(drawerView: View) {
    drawerView.translationX = 0f
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private fun registerBackAnimationCallback() {
    if (backDispatcher != null || !isAttachedToWindow) {
      return
    }
    findOnBackInvokedDispatcher()?.let { dispatcher ->
      dispatcher.registerOnBackInvokedCallback(
          OnBackInvokedDispatcher.PRIORITY_OVERLAY,
          backAnimationCallback,
      )
      backDispatcher = dispatcher
    }
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private fun unregisterBackAnimationCallback() {
    backDispatcher?.unregisterOnBackInvokedCallback(backAnimationCallback)
    backDispatcher = null
  }

  private fun applyContentTranslation(drawerView: View, slideOffset: Float) {
    if (childId == -1 || drawerView.width == 0) {
      return
    }

    val gravity = (drawerView.layoutParams as LayoutParams).gravity
    val view = findViewById<View>(childId) ?: return
    val absoluteGravity = GravityCompat.getAbsoluteGravity(gravity, layoutDirection)
    val isLeftDrawer = (absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.LEFT
    val maxOffset =
        if (isLeftDrawer) translationBehaviorStart.maxOffset
        else translationBehaviorEnd.maxOffset
    val direction = if (isLeftDrawer) 1 else -1
    view.translationX = direction * (drawerView.width * slideOffset) * maxOffset
  }

  private val mListener =
      object : SimpleDrawerListener() {
        override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
          applyContentTranslation(drawerView, slideOffset)
        }

        override fun onDrawerOpened(drawerView: View) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // DrawerLayout registers its own callback after dispatching this event. Register after
            // that pass so this callback owns the same predictive-back gesture.
            post {
              if (isDrawerOpen(drawerView)) {
                registerBackAnimationCallback()
              }
            }
          }
        }

        override fun onDrawerClosed(drawerView: View) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            unregisterBackAnimationCallback()
            // The predictive-back callback temporarily translates the drawer itself. Clear that
            // visual offset only after DrawerLayout has committed the closed state.
            drawerView.translationX = 0f
          }
          applyContentTranslation(drawerView, 0f)
        }
      }

  init {
    addDrawerListener(mListener)
  }

  /** Translation behavior for content view of [ContentTranslatingDrawerLayout]. */
  enum class TranslationBehavior(val maxOffset: Float) {

    /**
     * The default translation behavior. This makes the child view translate partially according to
     * the slide offset of the
     * [NavigationView][com.google.android.material.navigation.NavigationView]
     */
    DEFAULT(0.2f),

    /**
     * Makes the child child view translate according to the slide offset of the
     * [NavigationView][com.google.android.material.navigation.NavigationView]. The translation
     * offset is always equal to the slide offset in this behavior.
     */
    FULL(0.95f),
  }
}
