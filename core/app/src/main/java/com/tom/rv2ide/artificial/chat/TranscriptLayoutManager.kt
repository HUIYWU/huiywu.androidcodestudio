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

package com.tom.rv2ide.artificial.chat

import android.content.Context
import android.view.View
import android.widget.ScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Keeps the transcript from being scrolled by focus changes that originate inside a card.
 *
 * When a selectable text takes focus, RecyclerView brings the focused view back into view with a
 * rectangle built from the view's on-screen position. For text scrolled inside its card that
 * position sits one scroll amount above the card, so the resulting scroll was off by exactly that
 * amount: a card scrolled by a full area dragged the whole transcript up by that same distance,
 * starting with the first tap. Requests whose focused view lies in a scrolled vertical container
 * are dropped here; an unscrolled view keeps the stock reveal, and focus itself is untouched
 * either way.
 */
class TranscriptLayoutManager(context: Context) : LinearLayoutManager(context) {

    override fun onRequestChildFocus(
        recyclerView: RecyclerView,
        state: RecyclerView.State,
        child: View,
        focused: View?
    ): Boolean = focused != null && focused.liesInScrolledContainer(recyclerView)

    private fun View.liesInScrolledContainer(stopsAt: View): Boolean {
        var view = parent as? View
        while (view != null && view !== stopsAt) {
            if (view is ScrollView && view.scrollY > 0) return true
            view = view.parent as? View
        }
        return false
    }
}