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
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
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
      }

  override fun computeScroll() {
    super.computeScroll()

    for (index in 0 until childCount) {
      val drawerView = getChildAt(index)
      val gravity = (drawerView.layoutParams as? LayoutParams)?.gravity ?: continue
      val absoluteGravity = GravityCompat.getAbsoluteGravity(gravity, layoutDirection)
      val isDrawer =
          (absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.LEFT ||
              (absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT
      if (!isDrawer || drawerView.width == 0) {
        continue
      }

      val isLeftDrawer =
          (absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.LEFT
      val slideOffset =
          if (isLeftDrawer) {
            (drawerView.left + drawerView.width).toFloat() / drawerView.width
          } else {
            (width - drawerView.left).toFloat() / drawerView.width
          }
      applyContentTranslation(drawerView, slideOffset.coerceIn(0f, 1f))
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
