package com.tom.rv2ide.ui

import android.app.Activity
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import com.blankj.utilcode.util.SizeUtils
import com.tom.rv2ide.R
import com.tom.rv2ide.databinding.LayoutEditorQuickInputOverlayBinding
import com.tom.rv2ide.utils.Symbols.forFile
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.RenderScriptBlur

class EditorQuickInputOverlayController(
    private val host: FrameLayout,
) {
    private var binding: LayoutEditorQuickInputOverlayBinding? = null
    private var visible = false
    var onHidden: (() -> Unit)? = null

    fun isVisible(): Boolean = visible

    fun showFrom(anchorView: View, editor: CodeEditorView) {
        val overlay = ensureBinding()
        host.visibility = View.VISIBLE
        visible = true

        overlay.root.isClickable = false
        overlay.root.isFocusable = false
        overlay.root.setOnTouchListener { _, event ->
            val container = overlay.overlayContainer
            val x = event.x
            val y = event.y
            x >= container.x && x <= container.x + container.width &&
                y >= container.y && y <= container.y + container.height
        }
        overlay.overlayContainer.isClickable = true
        overlay.overlayContainer.isFocusable = true
        overlay.cardView.clipToOutline = true
        overlay.blurView.clipToOutline = false
        setupBlurEffect(overlay)

        overlay.symbolInput.refresh(editor.editor, forFile(editor.file))
        overlay.symbolInput.setExpandDirection(SymbolInputView.ExpandDirection.UP)
        overlay.symbolInput.expand()

        host.doOnLayout {
            val anchorRect = Rect()
            val hostRect = Rect()
            anchorView.getGlobalVisibleRect(anchorRect)
            host.getGlobalVisibleRect(hostRect)

            val localLeft = anchorRect.left - hostRect.left
            val localTop = anchorRect.top - hostRect.top
            val localBottom = anchorRect.bottom - hostRect.top

            val leadingSpaceWidth = SizeUtils.dp2px(40f)
            overlay.overlayContainer.x = (localLeft - leadingSpaceWidth).toFloat()
            overlay.overlayContainer.y = localTop.toFloat()
            overlay.overlayContainer.layoutParams = overlay.overlayContainer.layoutParams.apply {
                width = anchorRect.width() + leadingSpaceWidth
                height = FrameLayout.LayoutParams.WRAP_CONTENT
            }
            overlay.overlayContainer.alpha = 1f

            overlay.overlayContainer.doOnLayout {
                val collapsedTop = localBottom - anchorRect.height()
                val expandedTop = localBottom - overlay.overlayContainer.height
                overlay.overlayContainer.y = collapsedTop.toFloat()
                overlay.overlayContainer.animate()
                    .y(expandedTop.toFloat())
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

    private fun ensureBinding(): LayoutEditorQuickInputOverlayBinding {
        binding?.let { return it }
        val inflated = LayoutEditorQuickInputOverlayBinding.inflate(
            LayoutInflater.from(host.context),
            host,
            false,
        )
        host.removeAllViews()
        host.addView(inflated.root)
        binding = inflated
        return inflated
    }

    private fun setupBlurEffect(binding: LayoutEditorQuickInputOverlayBinding) {
        val activity = host.context as? Activity ?: return
        val blurTarget = activity.findViewById<BlurTarget>(R.id.blurTarget) ?: return
        try {
            binding.blurView.setupWith(
                blurTarget,
                RenderScriptBlur(host.context),
                40f,
                true,
            )
            binding.blurView.setOutlineProvider(ViewOutlineProvider.BACKGROUND)
            binding.blurView.clipToOutline = false
        } catch (_: Exception) {
        }
    }
}