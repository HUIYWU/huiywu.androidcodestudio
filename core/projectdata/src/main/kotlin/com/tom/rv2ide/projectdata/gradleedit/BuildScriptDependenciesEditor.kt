package com.tom.rv2ide.projectdata.gradleedit

object BuildScriptDependenciesEditor {
  private val supportedConfigurations = setOf(
      "implementation", "api", "compileOnly", "runtimeOnly",
      "testImplementation", "androidTestImplementation",
  )

  fun addProjectDependency(
      source: String,
      configuration: String,
      gradlePath: String,
      kotlinDsl: Boolean,
  ): GradleEditResult {
    if (configuration !in supportedConfigurations) {
      return GradleEditResult.Invalid("Unsupported dependency configuration: $configuration")
    }
    if (!gradlePath.startsWith(":")) return GradleEditResult.Invalid("Gradle path must start with ':'")
    if (containsProjectDependency(source, configuration, gradlePath)) return GradleEditResult.NoChange
    val blocks = GradleLexicalScanner.findNamedBlocks(source, "dependencies")
    if (blocks.size > 1) return GradleEditResult.Ambiguous("Multiple dependencies blocks found")
    if (blocks.isEmpty()) return GradleEditResult.Unsupported("No dependencies block found")
    val block = blocks.single()
    val lineIndent = indentationForBlock(source, block.openOffset)
    val dependency = if (kotlinDsl) {
      "${lineIndent}  $configuration(project(\"$gradlePath\"))"
    } else {
      "${lineIndent}  $configuration project('$gradlePath')"
    }
    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    return GradleEditResult.Applied(listOf(TextEdit(block.closeOffset, block.closeOffset, "$newline$dependency$newline$lineIndent")))
  }

  fun containsProjectDependency(source: String, configuration: String, gradlePath: String): Boolean {
    val quoted = Regex("project\\s*\\(\\s*[\\\"']${Regex.escape(gradlePath)}[\\\"']\\s*\\)")
    val groovy = Regex("project\\s*[\\(]\\s*[\\\"']${Regex.escape(gradlePath)}[\\\"']\\s*[\\)]?")
    return GradleLexicalScanner.findNamedBlocks(source, "dependencies").any { block ->
      val body = source.substring(block.openOffset + 1, block.closeOffset)
      Regex("\\b${Regex.escape(configuration)}\\b").containsMatchIn(body) &&
          (quoted.containsMatchIn(body) || groovy.containsMatchIn(body))
    }
  }

  private fun indentationForBlock(source: String, openOffset: Int): String {
    val lineStart = source.lastIndexOf('\n', openOffset).let { if (it < 0) 0 else it + 1 }
    return source.substring(lineStart, openOffset).takeWhile { it == ' ' || it == '\t' }
  }
}