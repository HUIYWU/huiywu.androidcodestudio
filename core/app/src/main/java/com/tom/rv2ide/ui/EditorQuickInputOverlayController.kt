package com.tom.rv2ide.ui

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import com.tom.rv2ide.databinding.LayoutEditorQuickInputOverlayBinding
import com.tom.rv2ide.utils.Symbols.forFile

class EditorQuickInputOverlayController(
    private val host: FrameLayout,
) {
    private var binding: LayoutEditorQuickInputOverlayBinding? = null
    private var visible = false
    var onHidden: (() -> Unit)? = null

    fun isVisible(): Boolean = visible

    fun showFrom(anchorView: View, editor: CodeEditorView) {
        val existing = binding
        if (existing == null) {
            val inflated = LayoutEditorQuickInputOverlayBinding.inflate(LayoutInflater.from(host.context), host, false)
            binding = inflated
            host.removeAllViews()
            host.addView(inflated.root)
            inflated.collapseButton.setOnClickListener { hide(true) }
            inflated.root.setOnClickListener { }
        }

        val overlay = binding ?: return
        host.visibility = View.VISIBLE
        visible = true
        overlay.symbolInput.refresh(editor.editor, forFile(editor.file))
        overlay.symbolInput.setExpandDirection(SymbolInputView.ExpandDirection.UP)
        overlay.symbolInput.collapse()

        host.doOnLayout {
            val anchorRect = Rect()
            val hostRect = Rect()
            anchorView.getGlobalVisibleRect(anchorRect)
            host.getGlobalVisibleRect(hostRect)
            val localLeft = anchorRect.left - hostRect.left
            val localTop = anchorRect.top - hostRect.top
            val localBottom = anchorRect.bottom - hostRect.top
            val collapsedHeight = anchorRect.height().coerceAtLeast(1)
            val expandedHeight = overlay.overlayHeader.layoutParams.height + overlay.symbolInput.layoutParams.height
            val targetTop = localBottom - expandedHeight

            overlay.overlayContainer.layoutParams = (overlay.overlayContainer.layoutParams).apply {
                width = anchorRect.width() + overlay.overlayContainer.paddingLeft + overlay.overlayContainer.paddingRight
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            overlay.overlayContainer.x = localLeft.toFloat()
            overlay.overlayContainer.y = localTop.toFloat()
            overlay.overlayContainer.alpha = 0.98f

            overlay.overlayContainer.doOnLayout {
                overlay.overlayContainer.animate()
                    .y(targetTop.toFloat())
                    .setDuration(180)
                    .start()
            }
        }
    }

    fun hide(animated: Boolean = true) {
        val overlay = binding
        if (overlay == null) {
            host.visibility = View.GONE
            visible = false
            onHidden?.invoke()
            return
        }
        val endAction = Runnable {
            host.removeAllViews()
            binding = null
            host.visibility = View.GONE
            visible = false
            onHidden?.invoke()
        }
        if (!animated) {
            endAction.run()
            return
        }
        overlay.overlayContainer.animate()
            .alpha(0f)
            .setDuration(120)
            .withEndAction(endAction)
            .start()
    }

    fun handleBackPress(): Boolean {
        if (!visible) return false
        hide(true)
        return true
    }
}
