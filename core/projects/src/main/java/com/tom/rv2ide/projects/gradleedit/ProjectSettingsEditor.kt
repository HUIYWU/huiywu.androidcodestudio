package com.tom.rv2ide.projects.gradleedit

/** Conservative editor for common settings.gradle(.kts) include and projectDir statements. */
object ProjectSettingsEditor {
  data class ProjectDirectoryMapping(val gradlePath: String, val directoryExpression: String)

  fun findProjectDirectoryMappings(source: String): List<ProjectDirectoryMapping> = mappingStatements(source).map {
    ProjectDirectoryMapping(it.gradlePath, it.directoryExpression)
  }

  fun addProjectDirMapping(source: String, gradlePath: String, directoryExpression: String, kotlinDsl: Boolean): GradleEditResult {
    if (!isGradlePath(gradlePath)) return GradleEditResult.Invalid("Gradle path must start with ':'")
    if (directoryExpression.isBlank()) return GradleEditResult.Invalid("Project directory must not be blank")
    if (mappingStatements(source).any { it.gradlePath == gradlePath }) return GradleEditResult.NoChange
    val quote = if (kotlinDsl) '"' else '\''
    return appendLine(source, "project($quote$gradlePath$quote).projectDir = file($quote$directoryExpression$quote)")
  }

  fun updateProjectDirMapping(source: String, gradlePath: String, directoryExpression: String, kotlinDsl: Boolean): GradleEditResult {
    if (!isGradlePath(gradlePath)) return GradleEditResult.Invalid("Gradle path must start with ':'")
    if (directoryExpression.isBlank()) return GradleEditResult.Invalid("Project directory must not be blank")
    val matches = mappingStatements(source).filter { it.gradlePath == gradlePath }
    if (matches.isEmpty()) return addProjectDirMapping(source, gradlePath, directoryExpression, kotlinDsl)
    if (matches.size > 1) return GradleEditResult.Ambiguous("Multiple projectDir mappings found for $gradlePath")
    val match = matches.single()
    val quote = if (kotlinDsl) '"' else '\''
    return GradleEditResult.Applied(listOf(TextEdit(match.expressionStart, match.expressionEnd, "$quote$directoryExpression$quote")))
  }

  fun removeProjectDirMapping(source: String, gradlePath: String): GradleEditResult {
    if (!isGradlePath(gradlePath)) return GradleEditResult.Invalid("Gradle path must start with ':'")
    val matches = mappingStatements(source).filter { it.gradlePath == gradlePath }
    if (matches.isEmpty()) return GradleEditResult.NoChange
    if (matches.size > 1) return GradleEditResult.Ambiguous("Multiple projectDir mappings found for $gradlePath")
    val match = matches.single()
    return GradleEditResult.Applied(listOf(TextEdit(match.statementStart, lineEndIncludingNewline(source, match.statementEnd), "")))
  }

  fun addInclude(source: String, gradlePath: String, kotlinDsl: Boolean): GradleEditResult {
    if (!isGradlePath(gradlePath)) return GradleEditResult.Invalid("Gradle path must start with ':'")
    if (findIncludedPaths(source).contains(gradlePath)) return GradleEditResult.NoChange
    val calls = GradleLexicalScanner.findCall(source, "include")
    val entry = quote(gradlePath, kotlinDsl)
    if (calls.size > 1) return GradleEditResult.Ambiguous("Multiple parenthesized include calls found")
    if (calls.size == 1) {
      val (open, close) = calls.single()
      val indentation = lineIndent(source, open)
      val newline = newline(source)
      val replacement = if (source.substring(open + 1, close).trim().isEmpty()) "$newline$indentation  $entry$newline$indentation" else ",$newline$indentation  $entry$newline$indentation"
      return GradleEditResult.Applied(listOf(TextEdit(close, close, replacement)))
    }
    return appendLine(source, "include($entry)")
  }

  /** Removes only a standalone include(":path") statement; compound and Groovy command syntax fail closed. */
  fun removeInclude(source: String, gradlePath: String): GradleEditResult {
    if (!isGradlePath(gradlePath)) return GradleEditResult.Invalid("Gradle path must start with ':'")
    val matches = GradleLexicalScanner.findCall(source, "include").filter { (open, close) ->
      val arguments = source.substring(open + 1, close).trim()
      arguments == "\"$gradlePath\"" || arguments == "'$gradlePath'"
    }
    if (matches.isEmpty()) return if (findIncludedPaths(source).contains(gradlePath)) {
      GradleEditResult.Unsupported("Include is not a standalone parenthesized statement")
    } else GradleEditResult.NoChange
    if (matches.size > 1) return GradleEditResult.Ambiguous("Multiple standalone includes found for $gradlePath")
    val (open, close) = matches.single()
    val statementStart = source.lastIndexOf('\n', open).let { if (it < 0) 0 else it + 1 }
    return GradleEditResult.Applied(listOf(TextEdit(statementStart, lineEndIncludingNewline(source, close), "")))
  }

  fun findIncludedPaths(source: String): Set<String> {
    val result = linkedSetOf<String>()
    GradleLexicalScanner.findCall(source, "include").forEach { (open, close) ->
      Regex("[\\\"'](:[A-Za-z0-9_:-]+)[\\\"']").findAll(source.substring(open + 1, close)).forEach { result += it.groupValues[1] }
    }
    source.lineSequence().forEach { line ->
      val code = line.substringBefore("//").trim()
      if (code.startsWith("include ")) Regex("[\\\"'](:[A-Za-z0-9_:-]+)[\\\"']").findAll(code).forEach { result += it.groupValues[1] }
    }
    return result
  }

  private data class MappingStatement(
      val gradlePath: String,
      val directoryExpression: String,
      val expressionStart: Int,
      val expressionEnd: Int,
      val statementStart: Int,
      val statementEnd: Int,
  )

  private fun mappingStatements(source: String): List<MappingStatement> {
    val result = mutableListOf<MappingStatement>()
    GradleLexicalScanner.findCall(source, "project").forEach { (open, close) ->
      val path = Regex("^[\\s]*[\\\"'](:[A-Za-z0-9_:-]+)[\\\"']\\s*$").find(source.substring(open + 1, close))?.groupValues?.get(1) ?: return@forEach
      val lineEnd = source.indexOf('\n', close).let { if (it < 0) source.length else it }
      val direct = Regex("\\.projectDir\\s*=\\s*file\\s*\\(\\s*([\\\"'][^\\r\\n)]*[\\\"'])\\s*\\)").find(source.substring(close + 1, lineEnd))
      if (direct != null) {
        val expressionStart = close + 1 + direct.range.first + direct.groupValues[0].indexOf(direct.groupValues[1])
        val statementStart = source.lastIndexOf('\n', open).let { if (it < 0) 0 else it + 1 }
        result += mapping(path, expressionStart, expressionStart + direct.groupValues[1].length, source, statementStart, lineEnd)
        return@forEach
      }
      val brace = GradleLexicalScanner.indexAfterWhitespace(source, close)
      if (brace >= source.length || source[brace] != '{') return@forEach
      val blockClose = GradleLexicalScanner.matchingBrace(source, brace) ?: return@forEach
      val nested = Regex("projectDir\\s*=\\s*file\\s*\\(\\s*([\\\"'][^\\r\\n)]*[\\\"'])\\s*\\)").find(source.substring(brace + 1, blockClose)) ?: return@forEach
      val expressionStart = brace + 1 + nested.range.first + nested.groupValues[0].indexOf(nested.groupValues[1])
      result += mapping(path, expressionStart, expressionStart + nested.groupValues[1].length, source, brace + 1 + nested.range.first, brace + 1 + nested.range.last + 1)
    }
    return result
  }

  private fun mapping(path: String, expressionStart: Int, expressionEnd: Int, source: String, statementStart: Int, statementEnd: Int): MappingStatement =
      MappingStatement(path, source.substring(expressionStart, expressionEnd), expressionStart, expressionEnd, statementStart, statementEnd)
  private fun appendLine(source: String, line: String): GradleEditResult {
    val nl = newline(source); val prefix = if (source.isEmpty() || source.endsWith(nl)) "" else nl
    return GradleEditResult.Applied(listOf(TextEdit(source.length, source.length, "$prefix$line$nl")))
  }
  private fun quote(path: String, kotlinDsl: Boolean) = if (kotlinDsl) "\"$path\"" else "'$path'"
  private fun isGradlePath(path: String) = path.matches(Regex(":[A-Za-z0-9_:-]+"))
  private fun newline(source: String) = if (source.contains("\r\n")) "\r\n" else "\n"
  private fun lineIndent(source: String, offset: Int): String { val start = source.lastIndexOf('\n', offset).let { if (it < 0) 0 else it + 1 }; return source.substring(start, offset).takeWhile { it == ' ' || it == '\t' } }
  private fun lineEndIncludingNewline(source: String, offset: Int): Int { val end = source.indexOf('\n', offset); return if (end < 0) source.length else end + 1 }
}