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

import android.app.Activity
import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.annotation.GravityInt
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.Insets
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.transition.TransitionManager
import com.blankj.utilcode.util.KeyboardUtils
import com.blankj.utilcode.util.SizeUtils
import com.blankj.utilcode.util.ThreadUtils.runOnUiThread
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import com.google.android.material.tabs.TabLayout.Tab
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.transition.MaterialSharedAxis
import com.tom.rv2ide.R
import com.tom.rv2ide.adapters.DiagnosticsAdapter
import com.tom.rv2ide.adapters.EditorBottomSheetTabAdapter
import com.tom.rv2ide.adapters.SearchListAdapter
import com.tom.rv2ide.databinding.LayoutEditorBottomSheetBinding
import com.tom.rv2ide.fragments.output.ShareableOutputFragment
import com.tom.rv2ide.models.LogLine
import com.tom.rv2ide.resources.R.string
import com.tom.rv2ide.tasks.TaskExecutor.CallbackWithError
import com.tom.rv2ide.tasks.TaskExecutor.executeAsync
import com.tom.rv2ide.tasks.TaskExecutor.executeAsyncProvideError
import com.tom.rv2ide.utils.IntentUtils.shareFile
import com.tom.rv2ide.utils.Symbols.forFile
import com.tom.rv2ide.utils.flashError
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.Callable
import kotlin.math.roundToInt
import org.slf4j.LoggerFactory
import eightbitlab.com.blurview.RenderScriptBlur
import android.view.ViewOutlineProvider
import eightbitlab.com.blurview.BlurTarget

/**
 * Bottom sheet shown in editor activity.
 *
 * @author Akash Yadav
 */
class EditorBottomSheet
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr, defStyleRes) {

  private val collapsedHeight: Float by lazy {
    val localContext = getContext() ?: return@lazy 0f
    localContext.resources.getDimension(R.dimen.editor_sheet_collapsed_height)
  }
  private val quickInputExpandedHeight: Int by lazy {
    SizeUtils.dp2px(136f)
  }
  private val behavior: BottomSheetBehavior<EditorBottomSheet> by lazy {
    BottomSheetBehavior.from(this).apply {
      isFitToContents = false
      skipCollapsed = true
    }
  }

  @JvmField var binding: LayoutEditorBottomSheetBinding
  val pagerAdapter: EditorBottomSheetTabAdapter
  private var anchorOffset = 0
  private var currentSheetOffset = 0f
  private var isImeVisible = false
  private var quickInputContainerAnimator: ValueAnimator? = null
  private var basicContainerChild = CHILD_HEADER
  private var windowInsets: Insets? = null
  private var currentSymbolInputEditor: CodeEditorView? = null

  private val insetBottom: Int
    get() = if (isImeVisible) 0 else windowInsets?.bottom ?: 0

  var requestShowQuickInputOverlay: (() -> Unit)? = null
  var requestHideQuickInputOverlay: (() -> Unit)? = null
  private var quickInputOverlayActive = false


  private enum class TopContainerMode {
    BASIC,
    SYMBOL_INPUT,
    HIDDEN,
  }

  companion object {

    private val log = LoggerFactory.getLogger(EditorBottomSheet::class.java)
    private const val START_HIDE_CONTAINER_AT_OFFSET = 0.82f
    private const val HIDE_CONTAINER_AT_OFFSET = 0.92f

    const val CHILD_HEADER = 0
    const val CHILD_SYMBOL_INPUT = 1
    const val CHILD_ACTION = 2
  }

  private fun canShareOutput(fragment: Fragment?): Boolean {
    return fragment is ShareableOutputFragment
  }

  private fun initialize(context: FragmentActivity) {
    val mediator =
        TabLayoutMediator(binding.tabs, binding.pager, true, true) { tab, position ->
          tab.text = pagerAdapter.getTitle(position)
        }

    mediator.attach()
    binding.pager.isUserInputEnabled = false
    binding.pager.offscreenPageLimit = pagerAdapter.itemCount - 1 // Do not remove any views

    binding.root.viewTreeObserver.addOnGlobalLayoutListener(
        object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                setupBlurEffect()
            }
        }
    )
    
    binding.tabs.addOnTabSelectedListener(
        object : OnTabSelectedListener {
          override fun onTabSelected(tab: Tab) {
            val fragment: Fragment = pagerAdapter.getFragmentAtIndex(tab.position)
            if (canShareOutput(fragment)) {
              binding.clearFab.show()
              binding.shareOutputFab.show()
            } else {
              binding.clearFab.hide()
              binding.shareOutputFab.hide()
            }

            applyTopContainerState()
          }
    
          override fun onTabUnselected(tab: Tab) {}
    
          override fun onTabReselected(tab: Tab) {}
        }
    )

    binding.shareOutputFab.setOnClickListener {
      val fragment = pagerAdapter.getFragmentAtIndex(binding.tabs.selectedTabPosition)

      if (fragment !is ShareableOutputFragment) {
        log.error("Unknown fragment: {}", fragment)
        return@setOnClickListener
      }

      val filename = fragment.getFilename()

      @Suppress("DEPRECATION")
      val progress =
          android.app.ProgressDialog.show(context, null, context.getString(string.please_wait))
      executeAsync(fragment::getContent) {
        progress.dismiss()
        shareText(it, filename)
      }
    }

    TooltipCompat.setTooltipText(binding.clearFab, context.getString(string.title_clear_output))
    binding.clearFab.setOnClickListener {
      val fragment: Fragment = pagerAdapter.getFragmentAtIndex(binding.tabs.selectedTabPosition)
      if (fragment !is ShareableOutputFragment) {
        log.error("Unknown fragment: {}", fragment)
        return@setOnClickListener
      }
      (fragment as ShareableOutputFragment).clearOutput()
    }

    binding.headerContainer.setOnClickListener {
      if (behavior.state != BottomSheetBehavior.STATE_EXPANDED) {
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
      }
    }

    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
      this.windowInsets = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
      insets
    }
  }

  init {
    if (context !is FragmentActivity) {
      throw IllegalArgumentException("EditorBottomSheet must be set up with a FragmentActivity")
    }

    val inflater = LayoutInflater.from(context)
    binding = LayoutEditorBottomSheetBinding.inflate(inflater)
    pagerAdapter = EditorBottomSheetTabAdapter(context)
    binding.pager.adapter = pagerAdapter
    clipChildren = false
    clipToPadding = false
    binding.root.clipChildren = false
    binding.root.clipToPadding = false
    binding.quickInputShell.clipChildren = false
    binding.quickInputShell.clipToPadding = false
    binding.cardView.scaleX = 0.9f
    binding.cardView.scaleY = 0.9f
    binding.cardView.clipToOutline = true
    binding.blurView.clipToOutline = false
    // This view owns the in-container quick input state.
    // DOWN expansion stays in this container so the bottom sheet can resize together with the panel.
    // UP expansion is delegated to a separate overlay, so this view acts only as the trigger, state source,
    // and geometry anchor for that path.
    binding.symbolInput.bindToggleButton(binding.quickInputToggle)
    binding.symbolInput.setExpansionChangeListener { expanded, direction ->
      log.info(
          "quickInput expansion changed: expanded={}, direction={}, useDown={}, sheetOffset={}",
          expanded,
          direction,
          shouldUseDownExpansion(),
          currentSheetOffset,
      )
      applyQuickInputExpansion(expanded, direction)
      if (direction == SymbolInputView.ExpandDirection.DOWN) {
        quickInputOverlayActive = false
      }
      binding.quickInputToggle.text =
          if ((expanded && direction == SymbolInputView.ExpandDirection.DOWN) || quickInputOverlayActive) "⌄" else "⌃"
    }
    binding.quickInputToggle.setOnClickListener {
      if (resolveTopContainerMode() != TopContainerMode.SYMBOL_INPUT) {
        return@setOnClickListener
      }
      if (shouldUseDownExpansion()) {
        // Keep DOWN expansion inside the current container.
        // The overlay must be hidden first so only one expanded surface is active at a time.
        requestHideQuickInputOverlay?.invoke()
        binding.symbolInput.setExpandDirection(SymbolInputView.ExpandDirection.DOWN)
        binding.symbolInput.toggleExpanded()
        setQuickInputOverlayActive(false)
      } else {
        // Do not render UP overflow inside the header container.
        // Collapse the local view and let the overlay own the expanded surface.
        binding.symbolInput.collapse()
        if (quickInputOverlayActive) {
          requestHideQuickInputOverlay?.invoke()
        } else {
          requestShowQuickInputOverlay?.invoke()
        }
      }
    }

    removeAllViews()
    addView(binding.root)

    initialize(context)
  }

  /** Set whether the input method is visible. */
  fun setImeVisible(isVisible: Boolean) {
    isImeVisible = isVisible
    behavior.isGestureInsetBottomIgnored = isVisible
  }

  fun setOffsetAnchor(view: View) {
    val listener =
        object : ViewTreeObserver.OnGlobalLayoutListener {
          override fun onGlobalLayout() {
            view.viewTreeObserver.removeOnGlobalLayoutListener(this)
            anchorOffset = view.height + SizeUtils.dp2px(1f)

            behavior.peekHeight = collapsedHeight.roundToInt()
            behavior.expandedOffset = anchorOffset
            behavior.isGestureInsetBottomIgnored = isImeVisible

            binding.root.updatePadding(bottom = anchorOffset + insetBottom)
            binding.headerContainer.apply {
              updatePaddingRelative(bottom = paddingBottom + insetBottom)
              updateLayoutParams<ViewGroup.LayoutParams> {
                height = (collapsedHeight + insetBottom).roundToInt()
              }
            }
          }
        }

    view.viewTreeObserver.addOnGlobalLayoutListener(listener)
  }

  fun onStateChanged(newState: Int) {
    currentSheetOffset =
        when (newState) {
          BottomSheetBehavior.STATE_EXPANDED -> 1f
          BottomSheetBehavior.STATE_COLLAPSED -> 0f
          else -> currentSheetOffset
        }
    log.info(
        "quickInput onStateChanged: newState={}, sheetOffset={}, topMode={}, symbolExpanded={}, contentExpanded={}, shellHeight={}, cardHeight={}, headerHeight={}",
        newState,
        currentSheetOffset,
        resolveTopContainerMode(),
        binding.symbolInput.isExpanded,
        binding.symbolInput.isContentExpanded,
        binding.quickInputShell.height,
        binding.cardView.height,
        binding.headerContainer.height,
    )
    applyTopContainerState(animated = true)
  }

  fun onSlide(sheetOffset: Float) {
    currentSheetOffset = sheetOffset
    log.info(
        "quickInput onSlide(before): sheetOffset={}, topMode={}, useDown={}, symbolExpanded={}, contentExpanded={}, shellHeight={}, cardHeight={}, headerHeight={}",
        currentSheetOffset,
        resolveTopContainerMode(),
        shouldUseDownExpansion(),
        binding.symbolInput.isExpanded,
        binding.symbolInput.isContentExpanded,
        binding.quickInputShell.height,
        binding.cardView.height,
        binding.headerContainer.height,
    )
    updateQuickInputExpandDirection()
    binding.symbolInput.collapse()
    binding.headerContainer.updatePaddingRelative(bottom = 0)
    applyTopContainerState()
    log.info(
        "quickInput onSlide(after): sheetOffset={}, topMode={}, useDown={}, symbolExpanded={}, contentExpanded={}, shellHeight={}, cardHeight={}, headerHeight={}",
        currentSheetOffset,
        resolveTopContainerMode(),
        shouldUseDownExpansion(),
        binding.symbolInput.isExpanded,
        binding.symbolInput.isContentExpanded,
        binding.quickInputShell.height,
        binding.cardView.height,
        binding.headerContainer.height,
    )
  }

  fun showChild(index: Int) {
    if (index != CHILD_SYMBOL_INPUT) {
      basicContainerChild = index
    }
    applyTopContainerState()
  }

  private fun updateQuickInputExpandDirection() {
    val direction =
        if (shouldUseDownExpansion()) {
          SymbolInputView.ExpandDirection.DOWN
        } else {
          SymbolInputView.ExpandDirection.UP
        }
    binding.symbolInput.setExpandDirection(direction)
  }

  private fun shouldExpandQuickInputDown(): Boolean {
    return currentSheetOffset > 0.08f && currentSheetOffset < HIDE_CONTAINER_AT_OFFSET
  }

  private fun shouldUseDownExpansion(): Boolean {
    return shouldExpandQuickInputDown()
  }

  private fun shouldSuppressSymbolInputForTerminal(): Boolean {
    return isTerminalTabSelected() && shouldUseDownExpansion()
  }

  private fun resolveTopContainerMode(): TopContainerMode {
    return if (shouldHideTopContainer()) {
      TopContainerMode.HIDDEN
    } else if (isImeVisible && !shouldSuppressSymbolInputForTerminal()) {
      TopContainerMode.SYMBOL_INPUT
    } else {
      TopContainerMode.BASIC
    }
  }


  private fun shouldHideTopContainer(): Boolean {
    return currentSheetOffset >= HIDE_CONTAINER_AT_OFFSET ||
        behavior.state == BottomSheetBehavior.STATE_EXPANDED
  }

  private fun topContainerVisibilityProgress(): Float {
    if (currentSheetOffset <= START_HIDE_CONTAINER_AT_OFFSET) {
      return 1f
    }
    if (currentSheetOffset >= HIDE_CONTAINER_AT_OFFSET) {
      return 0f
    }
    val range = HIDE_CONTAINER_AT_OFFSET - START_HIDE_CONTAINER_AT_OFFSET
    return (1f - ((currentSheetOffset - START_HIDE_CONTAINER_AT_OFFSET) / range)).coerceIn(0f, 1f)
  }

  private fun currentTopContainerHeight(): Int {
    return (collapsedHeight * topContainerVisibilityProgress()).roundToInt().coerceAtLeast(0)
  }
private fun applyTopContainerState(animated: Boolean = false) {
    val topMode = resolveTopContainerMode()
    log.info(
        "quickInput applyTopContainerState: animated={}, topMode={}, sheetOffset={}, useDown={}, symbolExpanded={}, contentExpanded={}, shellHeight={}, cardHeight={}, headerHeight={}",
        animated,
        topMode,
        currentSheetOffset,
        shouldUseDownExpansion(),
        binding.symbolInput.isExpanded,
        binding.symbolInput.isContentExpanded,
        binding.quickInputShell.height,
        binding.cardView.height,
        binding.headerContainer.height,
    )
    if (animated) {
      TransitionManager.beginDelayedTransition(
          binding.root,
          MaterialSharedAxis(MaterialSharedAxis.Y, false),
      )
    }
    when (topMode) {
      TopContainerMode.HIDDEN -> hideTopContainer()
      TopContainerMode.SYMBOL_INPUT -> showSymbolInputContainer()
      TopContainerMode.BASIC -> showBasicContainer()
    }
  }


  private fun setTopContainerHeight(height: Int) {
    binding.quickInputShell.updateLayoutParams<ViewGroup.LayoutParams> {
      this.height = height
    }
    binding.cardView.updateLayoutParams<LinearLayout.LayoutParams> {
      this.height = height
      gravity = android.view.Gravity.TOP
    }
    binding.headerContainer.updateLayoutParams<ViewGroup.LayoutParams> {
      this.height = height
    }
  }

  private fun hideTopContainer() {
    binding.symbolInput.collapse()
    binding.quickInputShell.visibility = View.VISIBLE
    binding.quickInputShell.alpha = 0f
    binding.quickInputShell.isEnabled = false
    binding.headerContainer.visibility = View.INVISIBLE
    binding.quickInputLeadingSpace.visibility = View.GONE
    binding.quickInputToggle.visibility = View.GONE
    binding.cardView.translationY = 0f
    binding.quickInputToggle.translationY = 0f
    setTopContainerHeight(0)
  }

  private fun showBasicContainer() {
    val height = currentTopContainerHeight()
    val progress = topContainerVisibilityProgress()
    binding.symbolInput.collapse()
    binding.quickInputShell.visibility = View.VISIBLE
    binding.quickInputShell.alpha = progress
    binding.quickInputShell.isEnabled = true
    binding.headerContainer.visibility = View.VISIBLE
    binding.headerContainer.displayedChild = basicContainerChild
    binding.quickInputLeadingSpace.visibility = View.GONE
    binding.quickInputToggle.visibility = View.GONE
    binding.cardView.scaleX = 0.9f
    binding.cardView.scaleY = 0.9f
    binding.cardView.translationY = 0f
    binding.quickInputToggle.translationY = 0f
    setTopContainerHeight(height)
  }

  private fun showSymbolInputContainer() {
    val height = currentTopContainerHeight()
    val progress = topContainerVisibilityProgress()
    log.info(
        "quickInput showSymbolInputContainer: targetHeight={}, progress={}, sheetOffset={}, useDown={}, symbolExpanded={}, contentExpanded={}, shellHeight(before)={}, cardHeight(before)={}, headerHeight(before)={}",
        height,
        progress,
        currentSheetOffset,
        shouldUseDownExpansion(),
        binding.symbolInput.isExpanded,
        binding.symbolInput.isContentExpanded,
        binding.quickInputShell.height,
        binding.cardView.height,
        binding.headerContainer.height,
    )
    binding.quickInputShell.visibility = View.VISIBLE
    binding.quickInputShell.alpha = progress
    binding.quickInputShell.isEnabled = true
    binding.headerContainer.visibility = View.VISIBLE
    binding.headerContainer.displayedChild = CHILD_SYMBOL_INPUT
    binding.quickInputLeadingSpace.visibility = View.VISIBLE
    binding.quickInputToggle.visibility = View.VISIBLE
    binding.cardView.scaleX = 1f
    binding.cardView.scaleY = 1f
    updateQuickInputExpandDirection()
    if (quickInputOverlayActive) {
      binding.quickInputToggle.text = "⌄"
    }
    setTopContainerHeight(height)
    log.info(
        "quickInput showSymbolInputContainer(applied): shellHeight(after)={}, cardHeight(after)={}, headerHeight(after)={}",
        binding.quickInputShell.height,
        binding.cardView.height,
        binding.headerContainer.height,
    )
  }

  // Applies the container-side expansion model.
  // The panel surface is defined by quickInputShell / cardView / headerContainer, not by SymbolInputView alone.
  // This keeps size, gravity, and sheet interaction consistent for the in-container path.
  private fun applyQuickInputExpansion(
      expanded: Boolean,
      direction: SymbolInputView.ExpandDirection,
  ) {
    val collapsed = collapsedHeight.roundToInt()
    val targetHeight = if (expanded) quickInputExpandedHeight else collapsed
    val expandUp = expanded && direction == SymbolInputView.ExpandDirection.UP
    val expandDown = expanded && direction == SymbolInputView.ExpandDirection.DOWN
    val shellTargetHeight = if (expandDown) targetHeight else collapsed

    log.info(
        "quickInput applyQuickInputExpansion(start): expanded={}, direction={}, expandDown={}, expandUp={}, collapsed={}, targetHeight={}, shellTargetHeight={}, shellHeight(before)={}, cardHeight(before)={}, headerHeight(before)={}, animatorActive={}",
        expanded,
        direction,
        expandDown,
        expandUp,
        collapsed,
        targetHeight,
        shellTargetHeight,
        binding.quickInputShell.height,
        binding.cardView.height,
        binding.headerContainer.height,
        quickInputContainerAnimator != null,
    )

    quickInputContainerAnimator?.cancel()
    binding.quickInputShell.bringToFront()
    binding.cardView.translationY = 0f
    binding.quickInputToggle.translationY = 0f
    binding.cardView.updateLayoutParams<LinearLayout.LayoutParams> {
      gravity = if (expandUp) android.view.Gravity.BOTTOM else android.view.Gravity.TOP
    }

    if (!expandDown) {
      binding.symbolInput.setContentExpanded(expanded)
      binding.quickInputShell.updateLayoutParams<ViewGroup.LayoutParams> {
        height = shellTargetHeight
      }
      binding.cardView.updateLayoutParams<LinearLayout.LayoutParams> {
        height = targetHeight
      }
      binding.headerContainer.updateLayoutParams<ViewGroup.LayoutParams> {
        height = targetHeight
      }
      log.info(
          "quickInput applyQuickInputExpansion(nonDownApplied): shellHeight(after)={}, cardHeight(after)={}, headerHeight(after)={}, contentExpanded={}",
          binding.quickInputShell.height,
          binding.cardView.height,
          binding.headerContainer.height,
          binding.symbolInput.isContentExpanded,
      )
      return
    }
    val startCardHeight = binding.cardView.height.takeIf { it > 0 } ?: collapsed
    val startShellHeight = binding.quickInputShell.height.takeIf { it > 0 } ?: collapsed
    if (!expanded) {
      binding.symbolInput.setContentTransitionProgress(1f)
    }

    log.info(
        "quickInput applyQuickInputExpansion(animatorInit): startCardHeight={}, startShellHeight={}, targetHeight={}, shellTargetHeight={}, collapsing={}",
        startCardHeight,
        startShellHeight,
        targetHeight,
        shellTargetHeight,
        !expanded,
    )

    quickInputContainerAnimator = ValueAnimator.ofInt(startCardHeight, targetHeight).apply {
      duration = 250
      interpolator = DecelerateInterpolator(1.5f)
      addUpdateListener { animator ->
        val progress = animator.animatedFraction.coerceIn(0f, 1f)
        val animatedHeight = (startCardHeight + ((targetHeight - startCardHeight) * progress)).roundToInt()

        val shellAnimatedHeight = (startShellHeight + ((shellTargetHeight - startShellHeight) * progress)).roundToInt()
        binding.quickInputShell.updateLayoutParams<ViewGroup.LayoutParams> {
          height = shellAnimatedHeight
        }
        binding.cardView.updateLayoutParams<LinearLayout.LayoutParams> {
          height = animatedHeight
        }
        binding.headerContainer.updateLayoutParams<ViewGroup.LayoutParams> {
          height = animatedHeight
        }

        val contentProgress = if (expanded) {
          ((progress - 0.2f) / 0.55f).coerceIn(0f, 1f)
        } else {
          (1f - (progress / 0.8f)).coerceIn(0f, 1f)
        }
        binding.symbolInput.setContentTransitionProgress(contentProgress)

        if (progress == 0f || progress >= 0.45f && progress <= 0.55f || progress == 1f) {
          log.info(
              "quickInput applyQuickInputExpansion(frame): progress={}, animatedHeight={}, shellAnimatedHeight={}, contentProgress={}, symbolExpanded={}, contentExpanded={}",
              progress,
              animatedHeight,
              shellAnimatedHeight,
              contentProgress,
              binding.symbolInput.isExpanded,
              binding.symbolInput.isContentExpanded,
          )
        }
      }
      doOnEnd {
        binding.symbolInput.setContentExpanded(expanded)

        binding.quickInputShell.updateLayoutParams<ViewGroup.LayoutParams> {
          height = shellTargetHeight
        }
        binding.cardView.updateLayoutParams<LinearLayout.LayoutParams> {
          height = targetHeight
        }
        binding.headerContainer.updateLayoutParams<ViewGroup.LayoutParams> {
          height = targetHeight
        }
        log.info(
            "quickInput applyQuickInputExpansion(end): expanded={}, shellHeight(after)={}, cardHeight(after)={}, headerHeight(after)={}, contentExpanded={}",
            expanded,
            binding.quickInputShell.height,
            binding.cardView.height,
            binding.headerContainer.height,
            binding.symbolInput.isContentExpanded,
        )
        quickInputContainerAnimator = null
      }
      start()
    }
  }
  fun setActionText(text: CharSequence) {
    binding.bottomAction.actionText.text = text
  }

  fun setActionProgress(progress: Int) {
    binding.bottomAction.progress.setProgressCompat(progress, true)
  }

  fun appendApkLog(line: io.github.mohammedbaqernull.logger.model.LogEntry) {
    pagerAdapter.logFragment?.appendLogToEditor(line)
  }

  fun appendBuildOut(str: String?) {
    pagerAdapter.buildOutputFragment?.appendOutput(str)
  }

  fun clearBuildOutput() {
    pagerAdapter.buildOutputFragment?.clearOutput()
  }

  fun handleDiagnosticsResultVisibility(errorVisible: Boolean) {
    runOnUiThread { pagerAdapter.diagnosticsFragment?.isEmpty = errorVisible }
  }

  fun handleSearchResultVisibility(errorVisible: Boolean) {
    runOnUiThread { pagerAdapter.searchResultFragment?.isEmpty = errorVisible }
  }

  fun setDiagnosticsAdapter(adapter: DiagnosticsAdapter) {
    runOnUiThread { pagerAdapter.diagnosticsFragment?.setAdapter(adapter) }
  }

  fun setSearchResultAdapter(adapter: SearchListAdapter) {
    runOnUiThread { pagerAdapter.searchResultFragment?.setAdapter(adapter) }
  }
  fun refreshSymbolInput(editor: CodeEditorView) {
    currentSymbolInputEditor = editor
    binding.symbolInput.refresh(editor.editor, forFile(editor.file))
  }

  fun getCurrentQuickInputEditor(): CodeEditorView? = currentSymbolInputEditor

  fun getQuickInputAnchorView(): View = binding.cardView

  fun setQuickInputOverlayActive(active: Boolean) {
    quickInputOverlayActive = active
    binding.quickInputToggle.isEnabled = true
    binding.quickInputToggle.alpha = 1f
    if (active) {
      binding.quickInputToggle.text = "⌄"
    } else if (!binding.symbolInput.isExpanded) {
      binding.quickInputToggle.text = "⌃"
    }
    binding.cardView.alpha = if (active) 0f else 1f
  }

  fun setQuickInputOverlayHandoffProgress(progress: Float) {
    binding.cardView.alpha = progress.coerceIn(0f, 1f)
  }

  fun isTerminalTabSelected(): Boolean {
    val fragment = pagerAdapter.getFragmentAtIndex(binding.tabs.selectedTabPosition)
    return fragment.javaClass.simpleName.contains("Terminal", ignoreCase = true)
  }

  fun onSoftInputChanged() {
    if (context !is Activity) {
      log.error("Bottom sheet is not attached to an activity!")
      return
    }

    binding.symbolInput.endItemAnimations()

    val activity = context as Activity
    isImeVisible = KeyboardUtils.isSoftInputVisible(activity)
    if (!isImeVisible) {
      setQuickInputOverlayActive(false)
    }
    applyTopContainerState(animated = true)
  }

  
  fun setStatus(text: CharSequence, @GravityInt gravity: Int) {
    runOnUiThread {
      binding.buildStatus.let {
        it.statusText.gravity = gravity
        it.statusText.text = text
      }
    }
  }

  private fun shareFile(file: File) {
    shareFile(context, file, "text/plain")
  }

  @Suppress("DEPRECATION")
  private fun shareText(text: String?, type: String) {
    if (text == null || TextUtils.isEmpty(text)) {
      flashError(context.getString(string.msg_output_text_extraction_failed))
      return
    }
    val pd =
        android.app.ProgressDialog.show(
            context,
            null,
            context.getString(string.please_wait),
            true,
            false,
        )
    executeAsyncProvideError(
        Callable { writeTempFile(text, type) },
        CallbackWithError<File> { result: File?, error: Throwable? ->
          pd.dismiss()
          if (result == null || error != null) {
            log.warn("Unable to share output", error)
            return@CallbackWithError
          }
          shareFile(result)
        },
    )
  }


  private fun setupBlurEffect() {
      binding.blurView.viewTreeObserver.addOnGlobalLayoutListener(
          object : ViewTreeObserver.OnGlobalLayoutListener {
              override fun onGlobalLayout() {
                  binding.blurView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                  
                  val activity = context as? Activity ?: return
                  
                  val blurTarget = activity.findViewById<BlurTarget>(R.id.blurTarget)
                  
                  if (blurTarget == null) {
                      return
                  }
                  
                  try {
                      binding.blurView.setupWith(
                          blurTarget,
                          RenderScriptBlur(context),
                          40f,
                          true
                      )
                      binding.blurView.setOutlineProvider(ViewOutlineProvider.BACKGROUND)
                      binding.blurView.setClipToOutline(false)

                  } catch (e: Exception) {
                      log.error("Blur setup failed", e)
                  }
              }
          }
      )
  }

  private fun writeTempFile(text: String, type: String): File {
    // use a common name to avoid multiple files
    val file: Path = context.filesDir.toPath().resolve("$type.txt")
    try {
      if (Files.exists(file)) {
        Files.delete(file)
      }
      Files.write(file, text.toByteArray(StandardCharsets.UTF_8), CREATE_NEW, WRITE)
    } catch (e: IOException) {
      log.error("Unable to write output to file", e)
    }
    return file.toFile()
  }
}
