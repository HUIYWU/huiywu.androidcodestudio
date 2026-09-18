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

package com.tom.rv2ide.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import com.google.android.material.textview.MaterialTextView

/**
 * Selectable text inside a vertical scroll area, nested in the chat transcript.
 *
 * Selectable text asks every ancestor to stop intercepting for the rest of the gesture, which
 * freezes both the enclosing scroll area and the transcript it lives in. The flag is confined to
 * its useful case here: cleared on press, so a plain drag scrolls the enclosing scroll area (while
 * the transcript above it is claimed so it cannot take the gesture), and set again while a
 * selection that started in this gesture is being dragged, so long-press selection keeps working.
 */
class ScrollFriendlySelectableTextView : MaterialTextView {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    private var hadSelectionAtPress = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hadSelectionAtPress = hasSelection()
                updateTouchOwnership(blockScroll = false)
            }
            MotionEvent.ACTION_MOVE ->
                updateTouchOwnership(blockScroll = hasSelection() && !hadSelectionAtPress)
        }
        return handled
    }

    private fun updateTouchOwnership(blockScroll: Boolean) {
        val scroll = scrollAncestor() ?: return
        scroll.requestDisallowInterceptTouchEvent(blockScroll)
        scroll.parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun scrollAncestor(): ScrollView? {
        var view = parent as? View
        while (view != null) {
            if (view is ScrollView) return view
            view = view.parent as? View
        }
        return null
    }
}