package com.tom.rv2ide.projects.gradleedit

/** Conservative editor for direct project dependencies in one dependencies block. */
object BuildScriptDependenciesEditor {
  private val supportedConfigurations = setOf("implementation", "api", "compileOnly", "runtimeOnly", "testImplementation", "androidTestImplementation")

  fun addProjectDependency(source: String, configuration: String, gradlePath: String, kotlinDsl: Boolean): GradleEditResult {
    if (configuration !in supportedConfigurations) return GradleEditResult.Invalid("Unsupported dependency configuration: $configuration")
    if (!isGradlePath(gradlePath)) return GradleEditResult.Invalid("Gradle path must start with ':'")
    val dependencies = dependencyStatements(source)
    if (dependencies.any { it.configuration == configuration && it.gradlePath == gradlePath }) return GradleEditResult.NoChange
    val blocks = dependencyBlocks(source)
    if (blocks.size > 1) return GradleEditResult.Ambiguous("Multiple dependencies blocks found")
    if (blocks.isEmpty()) return GradleEditResult.Unsupported("No dependencies block found")
    val block = blocks.single()
    val blockIndent = indentationForBlock(source, block.openOffset)
    val entryIndent = dependencyEntryIndent(source, block)
    val line = if (kotlinDsl) "$entryIndent$configuration(project(\"$gradlePath\"))" else "$entryIndent$configuration project('$gradlePath')"
    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    var insertion = block.closeOffset
    while (insertion > block.openOffset + 1 && source[insertion - 1].isWhitespace()) insertion--
    val replacement = "$newline$line$newline$blockIndent"
    return GradleEditResult.Applied(listOf(TextEdit(insertion, block.closeOffset, replacement)))
  }

  fun removeProjectDependency(source: String, configuration: String, gradlePath: String): GradleEditResult {
    if (configuration !in supportedConfigurations) return GradleEditResult.Invalid("Unsupported dependency configuration: $configuration")
    if (!isGradlePath(gradlePath)) return GradleEditResult.Invalid("Gradle path must start with ':'")
    val matches = dependencyStatements(source).filter { it.configuration == configuration && it.gradlePath == gradlePath }
    if (matches.isEmpty()) return GradleEditResult.NoChange
    if (matches.size > 1) return GradleEditResult.Ambiguous("Multiple $configuration dependencies found for $gradlePath")
    val match = matches.single()
    return GradleEditResult.Applied(listOf(TextEdit(lineStart(source, match.start), lineEndIncludingNewline(source, match.end), "")))
  }

  fun renameProjectDependency(source: String, configuration: String, oldGradlePath: String, newGradlePath: String): GradleEditResult {
    if (configuration !in supportedConfigurations) return GradleEditResult.Invalid("Unsupported dependency configuration: $configuration")
    if (!isGradlePath(oldGradlePath) || !isGradlePath(newGradlePath)) return GradleEditResult.Invalid("Gradle paths must start with ':'")
    if (oldGradlePath == newGradlePath) return GradleEditResult.NoChange
    val dependencies = dependencyStatements(source)
    if (dependencies.any { it.configuration == configuration && it.gradlePath == newGradlePath }) return GradleEditResult.Ambiguous("Target dependency already exists: $configuration $newGradlePath")
    val matches = dependencies.filter { it.configuration == configuration && it.gradlePath == oldGradlePath }
    if (matches.isEmpty()) return GradleEditResult.NoChange
    if (matches.size > 1) return GradleEditResult.Ambiguous("Multiple $configuration dependencies found for $oldGradlePath")
    val match = matches.single()
    val quote = source.getOrNull(match.pathStart)
    val replacementStart = if (quote == '\'' || quote == '"') match.pathStart + 1 else match.pathStart
    val replacementEnd = if (quote == '\'' || quote == '"') match.pathEnd - 1 else match.pathEnd
    return GradleEditResult.Applied(listOf(TextEdit(replacementStart, replacementEnd, newGradlePath)))
  }

  data class ProjectDependency(val configuration: String, val gradlePath: String)

  fun findProjectDependencies(source: String): List<ProjectDependency> =
      dependencyStatements(source).map { ProjectDependency(it.configuration, it.gradlePath) }

  /** Detects a project reference to [gradlePath] that is not one of the editor's removable forms. */
  fun hasUnsupportedProjectDependencyReference(source: String, gradlePath: String): Boolean {
    if (!isGradlePath(gradlePath)) return false
    val supportedRanges = dependencyStatements(source).filter { it.gradlePath == gradlePath }.map { it.start until it.end }
    return parsedCalls(source).filter { it.name == "project" }.any { call ->
      val path = call.arguments.orEmpty().singleOrNull()?.value
      path == gradlePath && supportedRanges.none { call.start in it }
    }
  }

  fun containsProjectDependency(source: String, configuration: String, gradlePath: String): Boolean =
      dependencyStatements(source).any { it.configuration == configuration && it.gradlePath == gradlePath }

  private data class DependencyStatement(val configuration: String, val gradlePath: String, val start: Int, val end: Int, val pathStart: Int, val pathEnd: Int)

  private fun dependencyStatements(source: String): List<DependencyStatement> {
    val blocks = dependencyBlocks(source)
    if (blocks.size != 1) return emptyList()
    val block = blocks.single()
    val calls = parsedCalls(source).filter { it.start >= block.openOffset && it.end <= block.closeOffset }
    val configurations = calls.filter { it.name in supportedConfigurations }
    val projects = calls.filter { it.name == "project" }
    return configurations.mapNotNull { configuration ->
      val nested = projects.filter { it.start > configuration.start && it.end <= configuration.end }
      if (nested.size != 1) return@mapNotNull null
      val project = nested.single()
      if (project.dynamic || project.arguments?.size != 1) return@mapNotNull null
      val path = project.arguments!!.single()
      DependencyStatement(
          configuration.name,
          path.value,
          configuration.start,
          configuration.end,
          path.start,
          path.end,
      )
    }
  }

  private fun parsedCalls(source: String): List<GradleParser.Call> {
    val groovy = GradleParser.parse(source, GradleDsl.GROOVY)
    val kotlin = GradleParser.parse(source, GradleDsl.KOTLIN)
    return distinctCalls(source, groovy + kotlin)
  }

  private fun dependencyBlocks(source: String): List<GradleLexicalScanner.Block> {
    val kotlinCalls = GradleParser.parse(source, GradleDsl.KOTLIN).filter { it.name == "dependencies" }
    val groovyCalls = GradleParser.parse(source, GradleDsl.GROOVY).filter { it.name == "dependencies" }
    val calls = distinctCalls(source, kotlinCalls + groovyCalls)
    val blocks = calls.mapNotNull { call ->
      // Kotlin call_expression and Groovy command_chain ranges include the closure.
      // Locate the opening brace after the call name, not after call.end.
      val callNameEnd = call.start + call.name.length
      val open = GradleLexicalScanner.indexAfterWhitespace(source, callNameEnd)
        .takeIf { it < source.length && source[it] == '{' }
        ?: return@mapNotNull null
      val close = GradleLexicalScanner.matchingBrace(source, open)
        ?: return@mapNotNull null
      GradleLexicalScanner.Block(open, close, '{')
    }
    return blocks
  }
  private fun distinctCalls(source: String, calls: List<GradleParser.Call>): List<GradleParser.Call> =
      calls.distinctBy { call ->
        "${call.name}:${call.start}"
      }

  private fun isGradlePath(path: String) = path.matches(Regex(":[A-Za-z0-9_:-]+"))

  private fun indentationForBlock(source: String, openOffset: Int): String = source.substring(source.lastIndexOf('\n', openOffset).let { if (it < 0) 0 else it + 1 }, openOffset).takeWhile { it == ' ' || it == '\t' }
  private fun dependencyEntryIndent(source: String, block: GradleLexicalScanner.Block): String {
    val blockIndent = indentationForBlock(source, block.openOffset)
    var lineStart = block.openOffset + 1
    while (lineStart < block.closeOffset) {
      val lineEnd = source.indexOf('\n', lineStart).let { if (it < 0 || it > block.closeOffset) block.closeOffset else it }
      val line = source.substring(lineStart, lineEnd)
      if (line.trim().isNotEmpty()) return line.takeWhile { it == ' ' || it == '\t' }
      lineStart = lineEnd + 1
    }
    return "$blockIndent  "
  }
  private fun lineStart(source: String, offset: Int): Int = source.lastIndexOf('\n', offset).let { if (it < 0) 0 else it + 1 }
  private fun lineEndIncludingNewline(source: String, offset: Int): Int = source.indexOf('\n', offset).let { if (it < 0) source.length else it + 1 }
}