package com.tom.rv2ide.editor.ui

import android.content.Context
import android.view.View
import android.widget.ScrollView

/** Editor popup window used to display language-server hover information. */
class HoverWindow(editor: IDEEditor) : BaseEditorWindow(editor) {

  private lateinit var scrollView: ScrollView
  private val resizeRunnable = Runnable {
    if (isShowing) {
      // IME animations resize the editor after the popup has already been measured. Re-measure
      // against the latest editor viewport instead of retaining a stale, partially clipped size.
      displayWindow()
    }
  }

  init {
    // Do not truncate by visual line count. Markdown paragraphs and wrapped source lines can reach
    // maxLines long before all KDoc sections are visible. The popup is bounded by the current
    // editor height, while the content remains available through this scroll container.
    text.maxLines = Int.MAX_VALUE
    text.ellipsize = null

    editor.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
      editor.removeCallbacks(resizeRunnable)
      editor.post(resizeRunnable)
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
