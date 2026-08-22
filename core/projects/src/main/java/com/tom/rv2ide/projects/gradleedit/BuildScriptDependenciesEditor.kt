package com.tom.rv2ide.projects.gradleedit

/** Conservative editor for direct project dependencies in one dependencies block. */
object BuildScriptDependenciesEditor {
  private val supportedConfigurations = setOf("implementation", "api", "compileOnly", "runtimeOnly", "testImplementation", "androidTestImplementation")

  fun addProjectDependency(source: String, configuration: String, gradlePath: String, kotlinDsl: Boolean): GradleEditResult {
    if (configuration !in supportedConfigurations) return GradleEditResult.Invalid("Unsupported dependency configuration: $configuration")
    if (!isGradlePath(gradlePath)) return GradleEditResult.Invalid("Gradle path must start with ':'")
    val dependencies = dependencyStatements(source)
    if (dependencies.any { it.configuration == configuration && it.gradlePath == gradlePath }) return GradleEditResult.NoChange
    val blocks = GradleLexicalScanner.findNamedBlocks(source, "dependencies")
    if (blocks.size > 1) return GradleEditResult.Ambiguous("Multiple dependencies blocks found")
    if (blocks.isEmpty()) return GradleEditResult.Unsupported("No dependencies block found")
    val block = blocks.single()
    val indent = indentationForBlock(source, block.openOffset)
    val line = if (kotlinDsl) "$indent  $configuration(project(\"$gradlePath\"))" else "$indent  $configuration project('$gradlePath')"
    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    return GradleEditResult.Applied(listOf(TextEdit(block.closeOffset, block.closeOffset, "$newline$line$newline$indent")))
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
    return GradleEditResult.Applied(listOf(TextEdit(match.pathStart, match.pathEnd, newGradlePath)))
  }

  data class ProjectDependency(val configuration: String, val gradlePath: String)

  fun findProjectDependencies(source: String): List<ProjectDependency> =
      dependencyStatements(source).map { ProjectDependency(it.configuration, it.gradlePath) }

  /** Detects a project reference to [gradlePath] that is not one of the editor's removable forms. */
  fun hasUnsupportedProjectDependencyReference(source: String, gradlePath: String): Boolean {
    if (!isGradlePath(gradlePath)) return false
    val supportedRanges = dependencyStatements(source).filter { it.gradlePath == gradlePath }.map { it.start until it.end }
    val candidate = Regex("project\\s*\\([^\\r\\n)]*[\\\"']${Regex.escape(gradlePath)}[\\\"'][^\\r\\n)]*\\)")
    return candidate.findAll(source).any { match ->
      GradleLexicalScanner.isCodeOffset(source, match.range.first) && supportedRanges.none { match.range.first in it }
    }
  }

  fun containsProjectDependency(source: String, configuration: String, gradlePath: String): Boolean =
      dependencyStatements(source).any { it.configuration == configuration && it.gradlePath == gradlePath }

  private data class DependencyStatement(val configuration: String, val gradlePath: String, val start: Int, val end: Int, val pathStart: Int, val pathEnd: Int)

  private fun dependencyStatements(source: String): List<DependencyStatement> {
    val blocks = GradleLexicalScanner.findNamedBlocks(source, "dependencies")
    val result = mutableListOf<DependencyStatement>()
    val expression = Regex("\\b(${supportedConfigurations.joinToString("|")})\\s*(?:\\(\\s*)?project\\s*\\(\\s*([\\\"'])(:[A-Za-z0-9_:-]+)\\2\\s*\\)\\s*\\)?")
    blocks.forEach { block ->
      val bodyStart = block.openOffset + 1
      val body = source.substring(bodyStart, block.closeOffset)
      expression.findAll(body).forEach { found ->
        val path = found.groupValues[3]
        val fullStart = bodyStart + found.range.first
        val pathStart = bodyStart + found.range.first + found.groupValues[0].indexOf(path)
        result += DependencyStatement(found.groupValues[1], path, fullStart, bodyStart + found.range.last + 1, pathStart, pathStart + path.length)
      }
    }
    return result
  }

  private fun isGradlePath(path: String) = path.matches(Regex(":[A-Za-z0-9_:-]+"))
  private fun indentationForBlock(source: String, openOffset: Int): String = source.substring(source.lastIndexOf('\n', openOffset).let { if (it < 0) 0 else it + 1 }, openOffset).takeWhile { it == ' ' || it == '\t' }
  private fun lineStart(source: String, offset: Int): Int = source.lastIndexOf('\n', offset).let { if (it < 0) 0 else it + 1 }
  private fun lineEndIncludingNewline(source: String, offset: Int): Int = source.indexOf('\n', offset).let { if (it < 0) source.length else it + 1 }
}