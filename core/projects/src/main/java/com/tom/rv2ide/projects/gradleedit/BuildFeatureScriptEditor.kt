package com.tom.rv2ide.projects.gradleedit

/**
 * Conservative editor for boolean flags declared inside the android buildFeatures block.
 * Only literal boolean values are rewritten; dynamic values and missing blocks fail closed.
 */
object BuildFeatureScriptEditor {
  private val supportedFeatures = setOf("viewBinding", "compose", "dataBinding", "mlModelBinding")

  /** Reads the literal boolean assigned to [feature] in the android buildFeatures block, or null when unknown. */
  fun findEnabled(source: String, feature: String, dsl: GradleDsl): Boolean? {
    if (feature !in supportedFeatures) return null
    val block = buildFeaturesBlock(source, dsl) ?: return null
    return block.features.filter { it.name == feature }.singleOrNull()?.enabled
  }

  /** Writes a literal boolean for [feature], appending a statement or a whole buildFeatures block when needed. */
  fun setBuildFeature(source: String, feature: String, enabled: Boolean, dsl: GradleDsl): GradleEditResult {
    if (feature !in supportedFeatures) return GradleEditResult.Invalid("Unsupported build feature: $feature")
    val androidBlocks = findAndroidBlocks(source, dsl)
    if (androidBlocks.isEmpty()) return GradleEditResult.Unsupported("No android block found")
    if (androidBlocks.size > 1) return GradleEditResult.Ambiguous("Multiple android blocks found")
    val androidBlock = androidBlocks.single()
    val blocks = GradleParser.parseBuildFeatures(source, dsl).filter {
      it.openOffset >= androidBlock.openOffset && it.closeOffset <= androidBlock.closeOffset
    }
    if (blocks.size > 1) return GradleEditResult.Ambiguous("Multiple buildFeatures blocks found")
    val block = blocks.singleOrNull()
    if (block != null) return replaceOrAppend(source, block, feature, enabled, dsl)
    return createBlock(source, androidBlock, feature, enabled, dsl)
  }

  private fun replaceOrAppend(
      source: String,
      block: GradleParser.BuildFeaturesBlock,
      feature: String,
      enabled: Boolean,
      dsl: GradleDsl,
  ): GradleEditResult {
    val matches = block.features.filter { it.name == feature }
    if (matches.size > 1) return GradleEditResult.Ambiguous("Multiple $feature assignments found")
    val match = matches.singleOrNull()
    if (match != null) {
      if (match.enabled == null) return GradleEditResult.Unsupported("$feature has a dynamic value")
      if (match.enabled == enabled) return GradleEditResult.NoChange
      return GradleEditResult.Applied(listOf(TextEdit(match.valueStart, match.valueEnd, enabled.toString())))
    }
    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    val blockIndent = indentationForBlock(source, block.openOffset)
    val entryIndent = blockEntryIndent(source, block.openOffset, block.closeOffset, blockIndent)
    val statement = featureStatement(feature, enabled, dsl)
    var insertion = block.closeOffset
    while (insertion > block.openOffset + 1 && source[insertion - 1].isWhitespace()) insertion--
    val replacement = "$newline$entryIndent$statement$newline$blockIndent"
    return GradleEditResult.Applied(listOf(TextEdit(insertion, block.closeOffset, replacement)))
  }

  private fun createBlock(
      source: String,
      androidBlock: GradleLexicalScanner.Block,
      feature: String,
      enabled: Boolean,
      dsl: GradleDsl,
  ): GradleEditResult {
    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    val androidIndent = indentationForBlock(source, androidBlock.openOffset)
    val entryIndent = blockEntryIndent(source, androidBlock.openOffset, androidBlock.closeOffset, androidIndent)
    val indentUnit = if (entryIndent.startsWith(androidIndent) && entryIndent.length > androidIndent.length) {
      entryIndent.substring(androidIndent.length)
    } else {
      "  "
    }
    val featureIndent = entryIndent + indentUnit
    val statement = featureStatement(feature, enabled, dsl)
    var insertion = androidBlock.closeOffset
    while (insertion > androidBlock.openOffset + 1 && source[insertion - 1].isWhitespace()) insertion--
    val replacement = "$newline${entryIndent}buildFeatures {$newline$featureIndent$statement$newline${entryIndent}}$newline$androidIndent"
    return GradleEditResult.Applied(listOf(TextEdit(insertion, androidBlock.closeOffset, replacement)))
  }

  private fun featureStatement(feature: String, enabled: Boolean, dsl: GradleDsl): String =
      if (dsl == GradleDsl.KOTLIN) "$feature = $enabled" else "$feature $enabled"

  private fun buildFeaturesBlock(source: String, dsl: GradleDsl): GradleParser.BuildFeaturesBlock? {
    val androidBlocks = findAndroidBlocks(source, dsl)
    if (androidBlocks.size != 1) return null
    val androidBlock = androidBlocks.single()
    return GradleParser.parseBuildFeatures(source, dsl).filter {
      it.openOffset >= androidBlock.openOffset && it.closeOffset <= androidBlock.closeOffset
    }.singleOrNull()
  }

  private fun findAndroidBlocks(source: String, dsl: GradleDsl): List<GradleLexicalScanner.Block> {
    val calls = GradleParser.parse(source, dsl).filter { it.name == "android" }
    return calls.mapNotNull { call ->
      // Kotlin call_expression and Groovy command_chain ranges include the closure.
      // Locate the opening brace after the call name, not after call.end.
      val callNameEnd = call.start + call.name.length
      val open = GradleLexicalScanner.indexAfterWhitespace(source, callNameEnd)
        .takeIf { it < source.length && source[it] == '{' }
        ?: return@mapNotNull null
      val close = GradleLexicalScanner.matchingBrace(source, open) ?: return@mapNotNull null
      GradleLexicalScanner.Block(open, close, '{')
    }
  }

  private fun indentationForBlock(source: String, openOffset: Int): String =
      source.substring(source.lastIndexOf('\n', openOffset).let { if (it < 0) 0 else it + 1 }, openOffset)
          .takeWhile { it == ' ' || it == '\t' }

  private fun blockEntryIndent(source: String, openOffset: Int, closeOffset: Int, blockIndent: String): String {
    var lineStart = openOffset + 1
    while (lineStart < closeOffset) {
      val lineEnd = source.indexOf('\n', lineStart).let { if (it < 0 || it > closeOffset) closeOffset else it }
      val line = source.substring(lineStart, lineEnd)
      if (line.trim().isNotEmpty()) return line.takeWhile { it == ' ' || it == '\t' }
      lineStart = lineEnd + 1
    }
    return "$blockIndent  "
  }
}