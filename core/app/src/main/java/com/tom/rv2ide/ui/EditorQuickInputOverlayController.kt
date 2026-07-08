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
import org.slf4j.LoggerFactory

// Hosts only the UP expansion surface.
// The regular quick input remains in the bottom sheet, while the overlay is used only for overflow that must
// render above the editor area and receive input outside the sheet container.
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
        // anchorView provides the starting bounds for the overlay path.
        // It defines position and card width reference, but it is not the expanded surface itself.
        // The toggle button stays outside the overlay card width.
        val overlay = ensureBinding()
        host.visibility = View.VISIBLE
        host.isClickable = false
        host.isFocusable = false
        visible = true

        overlay.root.isClickable = false
        overlay.root.isFocusable = false
        overlay.root.setOnTouchListener(null)
        overlay.overlayContainer.isClickable = false
        overlay.overlayContainer.isFocusable = false
        overlay.overlayContainer.setOnTouchListener(null)
        overlay.cardView.isClickable = false
        overlay.cardView.isFocusable = false
        overlay.cardView.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                log.debug("Overlay.card touch down local=({}, {}) card={}x{} x={} y={}", event.x, event.y, overlay.cardView.width, overlay.cardView.height, overlay.cardView.x, overlay.cardView.y)
            }
            false
        }
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
            log.debug("Overlay.layout localLeft={} localTop={} localBottom={} width={} host={}x{}", localLeft, localTop, localBottom, anchorRect.width() + leadingSpaceWidth, host.width, host.height)

            overlay.overlayContainer.doOnLayout {
                val expandedHeight = overlay.overlayContainer.height
                val collapsedHeight = anchorRect.height()
                val bottomY = localBottom
                log.debug("Overlay.measured container={}x{} card={}x{} symbol={}x{}", overlay.overlayContainer.width, overlay.overlayContainer.height, overlay.cardView.width, overlay.cardView.height, overlay.symbolInput.width, overlay.symbolInput.height)
                
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