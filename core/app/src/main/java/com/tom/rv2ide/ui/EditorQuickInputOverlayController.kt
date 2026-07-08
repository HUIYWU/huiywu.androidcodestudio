package com.tom.rv2ide.ui

import android.app.Activity
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import com.tom.rv2ide.R
import com.tom.rv2ide.databinding.LayoutEditorQuickInputOverlayBinding
import com.tom.rv2ide.utils.Symbols.forFile
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.RenderScriptBlur
import org.slf4j.LoggerFactory

class EditorQuickInputOverlayController(
    private val host: FrameLayout,
) {
    companion object {
        private val log = LoggerFactory.getLogger(EditorQuickInputOverlayController::class.java)
    }

    private var binding: LayoutEditorQuickInputOverlayBinding? = null
    private var visible = false
    var onHidden: (() -> Unit)? = null

    fun isVisible(): Boolean = visible

    fun showFrom(anchorView: View, editor: CodeEditorView) {
        val overlay = ensureBinding()
        log.debug("Overlay.showFrom enter hostVisibility={} hostWidth={} hostHeight={} anchorWidth={} anchorHeight={} file={}", host.visibility, host.width, host.height, anchorView.width, anchorView.height, editor.file?.absolutePath)
        host.visibility = View.VISIBLE
        visible = true

        overlay.root.isClickable = false
        overlay.root.isFocusable = false
        overlay.cardView.isClickable = true
        overlay.cardView.isFocusable = true
        overlay.cardView.clipToOutline = true
        overlay.blurView.clipToOutline = false
        overlay.root.setOnTouchListener { _, event ->
            val x = event.x - overlay.cardView.x
            val y = event.y - overlay.cardView.y
            x >= 0f && x <= overlay.cardView.width.toFloat() &&
                y >= 0f && y <= overlay.cardView.height.toFloat()
        }
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
            val localBottom = anchorRect.bottom - hostRect.top
            val collapsedTop = localBottom - anchorRect.height()
            log.debug("Overlay.showFrom layout hostRect={} anchorRect={} localLeft={} localBottom={} collapsedTop={}", hostRect, anchorRect, localLeft, localBottom, collapsedTop)

            overlay.cardView.layoutParams = (overlay.cardView.layoutParams as FrameLayout.LayoutParams).apply {
                width = anchorRect.width()
                height = FrameLayout.LayoutParams.WRAP_CONTENT
                leftMargin = localLeft
                topMargin = collapsedTop
            }
            overlay.cardView.alpha = 1f

            overlay.cardView.doOnLayout {
                val expandedTop = localBottom - overlay.cardView.height
                log.debug("Overlay.cardView measuredHeight={} symbolHeight={} expandedTop={} collapsedTop={} translationY={}", overlay.cardView.height, overlay.symbolInput.height, expandedTop, collapsedTop, (expandedTop - collapsedTop).toFloat())
                overlay.cardView.translationY = 0f
                overlay.cardView.animate()
                    .translationY((expandedTop - collapsedTop).toFloat())
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

        overlay.cardView.animate()
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