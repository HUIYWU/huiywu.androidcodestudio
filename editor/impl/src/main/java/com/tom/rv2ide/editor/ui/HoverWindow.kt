package com.tom.rv2ide.editor.ui

import android.content.Context
import android.view.View
import android.widget.ScrollView

/** Editor popup window used to display language-server hover information. */
class HoverWindow(editor: IDEEditor) : BaseEditorWindow(editor) {

  private lateinit var scrollView: ScrollView
  private val resizeRunnable = Runnable {
    if (isShowing) {
      // Only shrinking the viewport can clip a currently visible Hover. Expanding it when the
      // IME closes cannot hide content, and repeatedly calling displayWindow() during that
      // animation causes PopupWindow.update() to visibly jump/flicker.
      displayWindow()
    }
  }

  init {
    // Do not truncate by visual line count. Markdown paragraphs and wrapped source lines can reach
    // maxLines long before all KDoc sections are visible. The popup is bounded by the current
    // editor height, while the content remains available through this scroll container.
    text.maxLines = Int.MAX_VALUE
    text.ellipsize = null

    editor.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
      val width = right - left
      val height = bottom - top
      val oldWidth = oldRight - oldLeft
      val oldHeight = oldBottom - oldTop
      val viewportShrank = width < oldWidth || height < oldHeight

      if (viewportShrank && isShowing) {
        editor.removeCallbacks(resizeRunnable)
        editor.post(resizeRunnable)
      }
    }
  }

  override fun onCreateContentView(context: Context): View {
    return ScrollView(context).apply {
      isFillViewport = false
      isVerticalScrollBarEnabled = true
      overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
      addView(text)
      scrollView = this
    }
  }

  override fun getRootView(): View = scrollView

  fun showHover(content: CharSequence?) {
    if (content.isNullOrBlank()) {
      dismissHover()
      return
    }

    text.text = content
    scrollView.scrollTo(0, 0)
    displayWindow()
  }

  fun dismissHover() {
    if (isShowing) {
      dismiss()
    }
  }
}
