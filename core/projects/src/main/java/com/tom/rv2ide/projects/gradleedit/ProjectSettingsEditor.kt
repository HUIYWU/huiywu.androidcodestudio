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
      val body = source.substring(open + 1, close)
      val newline = newline(source)
      if (body.trim().isEmpty()) {
        val indentation = lineIndent(source, open)
        val replacement = "$newline$indentation  $entry$newline$indentation"
        return GradleEditResult.Applied(listOf(TextEdit(close, close, replacement)))
      }
      if (!body.contains('\n')) {
        return GradleEditResult.Applied(listOf(TextEdit(close, close, ", $entry")))
      }
      val entryIndentation = multilineEntryIndent(source, open, close)
      var insertion = close
      while (insertion > open + 1 && source[insertion - 1].isWhitespace()) insertion--
      val replacement = ",$newline$entryIndentation$entry$newline${lineIndent(source, open)}"
      return GradleEditResult.Applied(listOf(TextEdit(insertion, close, replacement)))
    }
    return appendLine(source, "include($entry)")
  }

  /**
   * Removes one literal path from a single include call or standalone include statement.
   * Dynamic arguments and multiple include calls remain fail-closed.
   */
  fun removeInclude(source: String, gradlePath: String): GradleEditResult {
    if (!isGradlePath(gradlePath)) return GradleEditResult.Invalid("Gradle path must start with ':'")
    val calls = includeCalls(source)
    val matchingCalls = calls.filter { call -> call.arguments.orEmpty().any { it.value == gradlePath } }
    if (matchingCalls.isEmpty()) return if (findIncludedPaths(source).contains(gradlePath)) {
      GradleEditResult.Unsupported("Include uses an unsupported or dynamic form")
    } else GradleEditResult.NoChange
    if (matchingCalls.size > 1 || calls.size > 1) return GradleEditResult.Ambiguous("Multiple include calls found for $gradlePath")

    val call = matchingCalls.single()
    val targetNode = call.arguments.orEmpty().filter { it.value == gradlePath }
    if (targetNode.size != 1) return GradleEditResult.Ambiguous("Multiple include entries found for $gradlePath")
    val literal = targetNode.single()
    val target = literalRange(source, literal.start, literal.end)
    val open = source.indexOf('(', call.start).takeIf { it >= 0 && it < call.end }
    val bodyStart: Int
    val bodyEnd: Int
    val before: String
    val after: String
    if (open != null) {
      val close = source.lastIndexOf(')', call.end - 1).takeIf { it > open }
          ?: return GradleEditResult.Unsupported("Include call has no complete argument list")
      bodyStart = open + 1
      bodyEnd = close
      before = source.substring(bodyStart, target.first)
      after = source.substring(target.second, bodyEnd)
    } else {
      bodyStart = call.start + call.name.length
      bodyEnd = call.end
      before = source.substring(bodyStart, target.first)
      after = source.substring(target.second, bodyEnd)
    }
    if (containsDynamicIncludeArgument(before) || containsDynamicIncludeArgument(after)) {
      return GradleEditResult.Unsupported("Include contains a dynamic argument")
    }

    val editRange = includeEntryRemovalRange(
        source,
        bodyStart,
        bodyEnd,
        target.first - bodyStart,
        target.second - bodyStart,
    )
    return GradleEditResult.Applied(listOf(TextEdit(editRange.first, editRange.second, "")))
  }

  // Legacy literal scanning removed; Tree-sitter supplies literal ranges.


  fun findIncludedPaths(source: String): Set<String> =
    includeCalls(source).flatMap { call -> call.arguments.orEmpty().map { it.value } }.toCollection(linkedSetOf())

  private fun includeCalls(source: String): List<GradleParser.Call> {
    val kotlin = GradleParser.parse(source, GradleDsl.KOTLIN).filter { it.name == "include" }
    val groovy = GradleParser.parse(source, GradleDsl.GROOVY).filter { it.name == "include" }
    return distinctCalls(source, kotlin + groovy).sortedBy { it.start }
  }


  private data class MappingStatement(
      val gradlePath: String,
      val directoryExpression: String,
      val expressionStart: Int,
      val expressionEnd: Int,
      val statementStart: Int,
      val statementEnd: Int,
  )

  private fun containsDynamicIncludeArgument(argument: String): Boolean {
    var index = 0
    while (index < argument.length) {
      when {
        argument[index].isWhitespace() || argument[index] == ',' -> index++
        argument.startsWith("//", index) -> {
          val end = argument.indexOf('\n', index + 2)
          index = if (end < 0) argument.length else end + 1
        }
        argument.startsWith("/*", index) -> {
          val end = argument.indexOf("*/", index + 2)
          if (end < 0) return true
          index = end + 2
        }
        argument[index] == '\'' || argument[index] == '"' -> {
          val quote = argument[index]
          val triple = index + 2 < argument.length && argument[index + 1] == quote && argument[index + 2] == quote
          val markerLength = if (triple) 3 else 1
          var cursor = index + markerLength
          var closed = false
          while (cursor < argument.length) {
            if (!triple && argument[cursor] == '\\') {
              cursor += 2
              continue
            }
            if (argument[cursor] == quote && (!triple || argument.startsWith(quote.toString().repeat(3), cursor))) {
              cursor += if (triple) 3 else 1
              closed = true
              break
            }
            cursor++
          }
          if (!closed) return true
          index = cursor
        }
        else -> return true
      }
    }
    return false
  }

  private fun includeEntryRemovalRange(
      source: String,
      bodyStart: Int,
      bodyEnd: Int,
      targetStartInBody: Int,
      targetEndInBody: Int,
  ): Pair<Int, Int> {
    val targetStart = bodyStart + targetStartInBody
    val targetEnd = bodyStart + targetEndInBody
    var before = targetStart - 1
    while (before >= bodyStart && source[before].isWhitespace()) before--
    var after = targetEnd
    while (after < bodyEnd && source[after].isWhitespace()) after++

    if (before >= bodyStart && source[before] == ',') {
      if (after < bodyEnd && source[after] == ',') {
        var end = after + 1
        while (end < bodyEnd && source[end].isWhitespace()) end++
        return targetStart to end
      }
      return before to targetEnd
    }
    if (after < bodyEnd && source[after] == ',') {
      var end = after + 1
      while (end < bodyEnd && source[end].isWhitespace()) end++
      return targetStart to end
    }

    val lineStart = source.lastIndexOf('\n', targetStart - 1).let { if (it < 0) 0 else it + 1 }
    val lineEnd = source.indexOf('\n', targetEnd).let { if (it < 0) source.length else it + 1 }
    return lineStart to lineEnd
  }

  private fun mappingStatements(source: String): List<MappingStatement> {
    val calls = parsedCalls(source)
    val projects = calls.filter { it.name == "project" && it.arguments?.size == 1 && !it.dynamic }
    val files = calls.filter { it.name == "file" && it.arguments?.size == 1 && !it.dynamic }
    return projects.mapNotNull { project ->
      val path = project.arguments!!.single()
      val searchEnd = mappingSearchEnd(source, project.end)
      val file = files.firstOrNull { it.start > project.end && it.end <= searchEnd }
        ?: return@mapNotNull null
      val directory = file.arguments!!.single()
      val between = source.substring(project.end, file.start)
      if (!between.contains("projectDir") || !between.contains('=')) return@mapNotNull null
      val statementStart = source.lastIndexOf('\n', project.start).let { if (it < 0) 0 else it + 1 }
      val statementEnd = if (searchEnd == source.length) file.end else searchEnd
      mapping(path.value, directory.start, directory.end, source, statementStart, statementEnd)
    }
  }

  private fun parsedCalls(source: String): List<GradleParser.Call> {
    val groovy = GradleParser.parse(source, GradleDsl.GROOVY)
    val kotlin = GradleParser.parse(source, GradleDsl.KOTLIN)
    return distinctCalls(source, groovy + kotlin)
  }

  private fun distinctCalls(source: String, calls: List<GradleParser.Call>): List<GradleParser.Call> =
      calls.distinctBy { call ->
        "${call.name}:${call.start}"
      }

  private fun mappingSearchEnd(source: String, start: Int): Int {
    val lineEnd = source.indexOf('\n', start).let { if (it < 0) source.length else it }
    val brace = GradleLexicalScanner.indexAfterWhitespace(source, start)
    if (brace < source.length && source[brace] == '{') {
      return GradleLexicalScanner.matchingBrace(source, brace) ?: lineEnd
    }
    return lineEnd
  }

  private fun literalRange(source: String, start: Int, end: Int): Pair<Int, Int> {
    var actualStart = start
    var actualEnd = end
    if (actualStart > 0 && (source[actualStart - 1] == '\'' || source[actualStart - 1] == '"')) actualStart--
    if (actualEnd < source.length && (source[actualEnd] == '\'' || source[actualEnd] == '"')) actualEnd++
    return actualStart to actualEnd
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
  private fun multilineEntryIndent(source: String, open: Int, close: Int): String {
    var lineStart = source.lastIndexOf('\n', close - 1).let { if (it < 0) open + 1 else it + 1 }
    while (lineStart > open) {
      val lineEnd = source.indexOf('\n', lineStart).let { if (it < 0 || it > close) close else it }
      if (source.substring(lineStart, lineEnd).trim().isNotEmpty()) {
        return source.substring(lineStart, lineEnd).takeWhile { it == ' ' || it == '\t' }
      }
      lineStart = source.lastIndexOf('\n', lineStart - 2).let { if (it < 0) open + 1 else it + 1 }
    }
    return lineIndent(source, open) + "  "
  }
  private fun lineEndIncludingNewline(source: String, offset: Int): Int { val end = source.indexOf('\n', offset); return if (end < 0) source.length else end + 1 }
}