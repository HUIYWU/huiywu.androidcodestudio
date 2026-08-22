package com.tom.rv2ide.projects.gradleedit

sealed interface GradleEditResult {
  data class Applied(val edits: List<TextEdit>) : GradleEditResult
  data object NoChange : GradleEditResult
  data class Unsupported(val reason: String) : GradleEditResult
  data class Ambiguous(val reason: String) : GradleEditResult
  data class Invalid(val reason: String) : GradleEditResult
}