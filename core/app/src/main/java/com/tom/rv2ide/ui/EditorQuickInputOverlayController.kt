package com.tom.rv2ide.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import com.blankj.utilcode.util.SizeUtils
import com.tom.rv2ide.R
import com.tom.rv2ide.databinding.LayoutEditorQuickInputOverlayBinding
import com.tom.rv2ide.utils.Symbols.forFile
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.RenderScriptBlur

// Hosts only the UP expansion surface.
// The regular quick input remains in the bottom sheet, while the overlay is used only for overflow that must
// render above the editor area and receive input outside the sheet container.
class EditorQuickInputOverlayController(
    private val host: FrameLayout,
) {
    private var binding: LayoutEditorQuickInputOverlayBinding? = null
    private var visible = false
    var onHidden: (() -> Unit)? = null

    fun isVisible(): Boolean = visible

    fun showFrom(anchorView: View, editor: CodeEditorView) {
        // anchorView provides the starting bounds for the overlay path.
        // It defines position and card width reference, but it is not the expanded surface itself.
        // The toggle button stays outside the overlay card width.
        val overlay = ensureBinding()
        host.visibility = View.VISIBLE
        visible = true

        overlay.root.isClickable = false
        overlay.root.isFocusable = false
        overlay.root.setOnTouchListener { _, event ->
            val x = event.x + overlay.root.scrollX
            val y = event.y + overlay.root.scrollY
            val left = overlay.overlayContainer.x
            val top = overlay.overlayContainer.y
            val right = left + overlay.overlayContainer.width
            val bottom = top + overlay.overlayContainer.height
            x >= left && x <= right && y >= top && y <= bottom
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

            // overlay_container keeps a leading spacer so the overlay card matches the same horizontal relationship
            // as the in-container card: reserved space on the left, card content on the right, toggle outside.
            // The total overlay width includes that spacer, and x is shifted left by the same amount so the card
            // itself stays aligned with the anchor card.
            val leadingSpaceWidth = SizeUtils.dp2px(40f)
            overlay.overlayContainer.x = (localLeft - leadingSpaceWidth).toFloat()
            overlay.overlayContainer.y = localTop.toFloat()
            overlay.overlayContainer.layoutParams = (overlay.overlayContainer.layoutParams as FrameLayout.LayoutParams).apply {
                width = anchorRect.width() + leadingSpaceWidth
                height = FrameLayout.LayoutParams.WRAP_CONTENT
            }
            overlay.overlayContainer.alpha = 0.8f

            overlay.overlayContainer.doOnLayout {
                val expandedHeight = overlay.overlayContainer.height
                val collapsedHeight = anchorRect.height()
                val bottomY = localBottom
                
                // Set initial height to collapsed, position so bottom aligns
                overlay.overlayContainer.layoutParams = (overlay.overlayContainer.layoutParams as FrameLayout.LayoutParams).apply {
                    height = collapsedHeight
                }
                overlay.overlayContainer.y = (bottomY - collapsedHeight).toFloat()
                overlay.overlayContainer.alpha = 0.8f
                
                // Animate height from collapsed to expanded, keeping bottom anchored
                ValueAnimator.ofInt(collapsedHeight, expandedHeight).apply {
                    duration = 250
                    interpolator = DecelerateInterpolator(1.5f)
                    addUpdateListener { animator ->
                        val animatedHeight = animator.animatedValue as Int
                        overlay.overlayContainer.layoutParams = (overlay.overlayContainer.layoutParams as FrameLayout.LayoutParams).apply {
                            height = animatedHeight
                        }
                        // Keep bottom edge anchored
                        overlay.overlayContainer.y = (bottomY - animatedHeight).toFloat()
                    }
                    start()
                }
                
                // Fade in alpha separately
                overlay.overlayContainer.animate()
                    .alpha(1f)
                    .setDuration(150)
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