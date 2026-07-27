package com.tom.rv2ide.editor.ui

import android.text.TextUtils

/** Editor popup window used to display language-server hover information. */
class HoverWindow(editor: IDEEditor) : BaseEditorWindow(editor) {

  init {
    text.maxLines = 27
    text.ellipsize = TextUtils.TruncateAt.END
  }

  fun showHover(content: CharSequence?) {
    if (content.isNullOrBlank()) {
      dismissHover()
      return
    }

    text.text = content
    displayWindow()
  }

  fun dismissHover() {
    if (isShowing) {
      dismiss()
    }
  }
}
