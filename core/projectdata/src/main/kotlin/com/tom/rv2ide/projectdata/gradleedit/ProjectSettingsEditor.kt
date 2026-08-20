package com.tom.rv2ide.projectdata.gradleedit
object ProjectSettingsEditor {
  data class ProjectDirectoryMapping(
      val gradlePath: String,
      val directoryExpression: String,
  )

  fun findProjectDirectoryMappings(source: String): List<ProjectDirectoryMapping> {
    val result = mutableListOf<ProjectDirectoryMapping>()
    GradleLexicalScanner.findCall(source, "project").forEach { (open, close) ->
      val projectBody = source.substring(open + 1, close)
      val path = Regex("^[\\s]*[\\\"'](:[A-Za-z0-9_:-]+)[\\\"']\\s*$")
          .find(projectBody)?.groupValues?.get(1) ?: return@forEach
      val lineEnd = source.indexOf('\n', close).let { if (it < 0) source.length else it }
      val tail = source.substring(close + 1, lineEnd)
      val mapping = Regex("\\.projectDir\\s*=\\s*file\\s*\\(([^)]*)\\)").find(tail)
          ?: return@forEach
      result += ProjectDirectoryMapping(path, mapping.groupValues[1].trim())
    }
    return result
  }

  fun addProjectDirMapping(
      source: String,
      gradlePath: String,
      directoryExpression: String,
      kotlinDsl: Boolean,
  ): GradleEditResult {
    if (!gradlePath.startsWith(":")) return GradleEditResult.Invalid("Gradle path must start with ':'")
    if (directoryExpression.isBlank()) return GradleEditResult.Invalid("Project directory must not be blank")
    if (findProjectDirectoryMappings(source).any { it.gradlePath == gradlePath }) return GradleEditResult.NoChange
    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    val quote = if (kotlinDsl) '"' else '\''
    val line = "project($quote$gradlePath$quote).projectDir = file($quote$directoryExpression$quote)"
    val prefix = if (source.isEmpty() || source.endsWith(newline)) "" else newline
    return GradleEditResult.Applied(listOf(TextEdit(source.length, source.length, "${prefix}$line$newline")))
  }


  fun addInclude(source: String, gradlePath: String, kotlinDsl: Boolean): GradleEditResult {
    if (!gradlePath.startsWith(":")) return GradleEditResult.Invalid("Gradle path must start with ':'")
    if (findIncludedPaths(source).contains(gradlePath)) return GradleEditResult.NoChange
    val calls = GradleLexicalScanner.findCall(source, "include")
    val entry = quote(gradlePath, kotlinDsl)
    if (calls.isNotEmpty()) {
      val (open, close) = calls.first()
      val indentation = lineIndent(source, open)
      val newline = if (source.contains("\r\n")) "\r\n" else "\n"
      val replacement = if (source.substring(open + 1, close).trim().isEmpty()) {
        "$newline$indentation  $entry$newline$indentation"
      } else {
        ",$newline$indentation  $entry$newline$indentation"
      }
      return GradleEditResult.Applied(listOf(TextEdit(close, close, replacement)))
    }
    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    val prefix = if (source.isEmpty() || source.endsWith(newline)) "" else newline
    return GradleEditResult.Applied(listOf(TextEdit(source.length, source.length, "${prefix}include($entry)$newline")))
  }

  fun findIncludedPaths(source: String): Set<String> {
    val result = linkedSetOf<String>()
    GradleLexicalScanner.findCall(source, "include").forEach { (open, close) ->
      val body = source.substring(open + 1, close)
      Regex("[\\\"'](:[A-Za-z0-9_:-]+)[\\\"']").findAll(body).forEach { result += it.groupValues[1] }
    }
    // Groovy also permits: include ':app', ':feature'. Keep this fallback line-oriented.
    source.lineSequence().forEach { line ->
      val code = line.substringBefore("//").trim()
      if (code.startsWith("include ")) {
        Regex("[\\\"'](:[A-Za-z0-9_:-]+)[\\\"']").findAll(code)
            .forEach { result += it.groupValues[1] }
      }
    }
    return result
  }

  private fun quote(path: String, kotlinDsl: Boolean): String =
      if (kotlinDsl) "\"$path\"" else "'$path'"

  private fun lineIndent(source: String, offset: Int): String {
    val lineStart = source.lastIndexOf('\n', offset).let { if (it < 0) 0 else it + 1 }
    return source.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
  }
}