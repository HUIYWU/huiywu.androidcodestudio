package com.tom.rv2ide.projects.gradleedit

/** A range-preserving edit applied to a source file. */
data class TextEdit(
    val startOffset: Int,
    val endOffset: Int,
    val replacement: String,
) {
  init {
    require(startOffset >= 0) { "startOffset must be non-negative" }
    require(endOffset >= startOffset) { "endOffset must not precede startOffset" }
  }
}

object TextEditApplier {
  fun apply(source: String, edits: List<TextEdit>): String {
    if (edits.isEmpty()) return source
    val ordered = edits.sortedByDescending { it.startOffset }
    var previousStart = source.length + 1
    ordered.forEach { edit ->
      require(edit.endOffset <= source.length) { "Edit exceeds source length" }
      require(edit.endOffset <= previousStart) { "Overlapping text edits" }
      previousStart = edit.startOffset
    }
    var result = source
    ordered.forEach { edit -> result = result.replaceRange(edit.startOffset, edit.endOffset, edit.replacement) }
    return result
  }
}
