package com.tom.rv2ide.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
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
    private var lastCollapsedHeight = 0
    private var lastBottomY = 0
    var onHidden: (() -> Unit)? = null
    var onHandoffProgress: ((Float) -> Unit)? = null

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
        overlay.cardView.setOnTouchListener(null)
        overlay.cardView.clipToOutline = true
        // Keep BlurView as a pure background surface.
        // The interactive SymbolInputView stays above it as a sibling so ripple/pressed feedback remains visible.
        overlay.blurView.clipToOutline = false
        overlay.blurView.isClickable = false
        overlay.blurView.isFocusable = false
        setupBlurEffect(overlay)
        overlay.blurView.post {
            overlay.blurView.invalidate()
        }

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
                lastCollapsedHeight = collapsedHeight
                lastBottomY = bottomY

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
            onHandoffProgress?.invoke(1f)
            onHidden?.invoke()
            return
        }

        val endAction = Runnable {
            onHandoffProgress?.invoke(1f)
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

        val targetCollapsedHeight = lastCollapsedHeight.takeIf { it > 0 } ?: overlay.cardView.height
        val bottomY = lastBottomY.takeIf { it > 0 } ?: (overlay.overlayContainer.y + overlay.overlayContainer.height).toInt()
        val startHeight = overlay.overlayContainer.height

        overlay.overlayContainer.animate().cancel()
        overlay.overlayContainer.alpha = 1f

        val collapsedTopOffset = SizeUtils.dp2px(6f).toFloat()
        ValueAnimator.ofInt(startHeight, targetCollapsedHeight).apply {
            duration = 220
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { animator ->
                val animatedHeight = animator.animatedValue as Int
                overlay.overlayContainer.layoutParams =
                    (overlay.overlayContainer.layoutParams as FrameLayout.LayoutParams).apply {
                        height = animatedHeight
                    }
                overlay.overlayContainer.y = (bottomY - animatedHeight).toFloat()
                val progress = if (startHeight == targetCollapsedHeight) 1f else {
                    (animatedHeight - targetCollapsedHeight).toFloat() / (startHeight - targetCollapsedHeight).toFloat()
                }
                val clampedProgress = progress.coerceIn(0f, 1f)
                val handoffStart = 0.08f
                val handoffProgress = (((1f - clampedProgress) - handoffStart) / (1f - handoffStart)).coerceIn(0f, 1f)
                val baseAlpha = 0.86f + (0.14f * clampedProgress)
                val overlayAlpha = when {
                    handoffProgress <= 0f -> baseAlpha
                    handoffProgress < 0.6f -> baseAlpha * (1f - 0.25f * (handoffProgress / 0.6f))
                    handoffProgress < 0.9f -> baseAlpha * (1f - 0.25f - 0.75f * ((handoffProgress - 0.6f) / 0.3f))
                    else -> 0f
                }
                overlay.overlayContainer.alpha = overlayAlpha
                // Nudge the overlay's collapsed row upward near handoff so it better matches
                // the bottom-sheet host's folded baseline before ownership transfers back.
                overlay.symbolInput.translationY = -(1f - clampedProgress) * collapsedTopOffset
                onHandoffProgress?.invoke(handoffProgress)
            }
            doOnEnd {
                overlay.symbolInput.translationY = -collapsedTopOffset
                overlay.overlayContainer.animate()
                    .alpha(0f)
                    .setDuration(90)
                    .withEndAction(endAction)
                    .start()
            }
            start()
        }
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