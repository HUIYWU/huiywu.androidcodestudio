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

package com.tom.rv2ide.artificial.completion

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.databinding.ViewSuggestionBinding
import org.slf4j.LoggerFactory

class SuggestionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private val log = LoggerFactory.getLogger(SuggestionView::class.java)
    }

    private val binding: ViewSuggestionBinding
    private var onSuggestionClickListener: (() -> Unit)? = null

    init {
        binding = ViewSuggestionBinding.inflate(LayoutInflater.from(context), this, true)
        
        visibility = View.GONE
        elevation = 12f
        translationZ = 12f
        binding.root.setOnClickListener {
            if (IdeLogConfig.shouldLogDebug()) {
                log.debug("CLICKED!!!")
            }
            onSuggestionClickListener?.invoke()
        }
    }

    fun showSuggestion(suggestion: String, editor: io.github.rosemoe.sora.widget.CodeEditor) {
        if (IdeLogConfig.shouldLogDebug()) {
            log.debug("showSuggestion called with: {}", suggestion)
        }

        binding.suggestionText.text = suggestion
        
        val cursor = editor.cursor
        val line = cursor.leftLine
        val column = cursor.leftColumn
        
        val cursorX = editor.getCharOffsetX(line, column)
        val cursorY = editor.getRowTop(line) - editor.offsetY
        
        measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        
        val suggestionHeight = measuredHeight
        val lineHeight = editor.rowHeight
        
        val params = layoutParams as FrameLayout.LayoutParams
        params.leftMargin = cursorX.toInt()
        
        params.topMargin = if (cursorY - suggestionHeight < 0) {
            (cursorY + lineHeight + 8).toInt()
        } else {
            (cursorY - suggestionHeight - 8).toInt()
        }
        
        layoutParams = params
        
        visibility = View.VISIBLE
        bringToFront()
        requestLayout()
        
        alpha = 0f
        animate()
            .alpha(1f)
            .setDuration(150)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                if (IdeLogConfig.shouldLogDebug()) {
                    log.debug("Animation complete, view visible and clickable")
                }
            }
            .start()
    }

    fun hideSuggestion() {
        if (IdeLogConfig.shouldLogDebug()) {
            log.debug("hideSuggestion called")
        }

        animate()
            .alpha(0f)
            .setDuration(100)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                visibility = View.GONE
                if (IdeLogConfig.shouldLogDebug()) {
                    log.debug("View hidden")
                }
            }
            .start()
    }

    fun setOnSuggestionClickListener(listener: () -> Unit) {
        if (IdeLogConfig.shouldLogDebug()) {
            log.debug("setOnSuggestionClickListener called")
        }
        onSuggestionClickListener = listener
    }

}