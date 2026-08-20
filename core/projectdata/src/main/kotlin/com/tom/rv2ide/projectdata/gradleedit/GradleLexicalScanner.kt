package com.tom.rv2ide.projectdata.gradleedit

/** Lightweight source scanner used to find real Gradle blocks without parsing the DSL. */
object GradleLexicalScanner {
  data class Block(val openOffset: Int, val closeOffset: Int, val kind: Char)

  fun matchingBrace(source: String, openOffset: Int): Int? =
      matching(source, openOffset, '{', '}')

  fun matchingParenthesis(source: String, openOffset: Int): Int? =
      matching(source, openOffset, '(', ')')

  fun findTokenOffsets(source: String, name: String): List<Int> =
      findCall(source, name).map { it.first - name.length }

  fun findCall(source: String, name: String): List<Pair<Int, Int>> {
    val result = mutableListOf<Pair<Int, Int>>()
    var index = 0
    while (index < source.length) {
      val token = nextCodeToken(source, index) ?: break
      index = token.second
      if (token.first != name) continue
      var cursor = skipWhitespace(source, index)
      if (cursor >= source.length || source[cursor] != '(') continue
      val close = matchingParenthesis(source, cursor) ?: continue
      result += cursor to close
      index = close + 1
    }
    return result
  }

  fun findNamedBlocks(source: String, name: String): List<Block> {
    val result = mutableListOf<Block>()
    var index = 0
    while (index < source.length) {
      val token = nextCodeToken(source, index) ?: break
      index = token.second
      if (token.first != name) continue
      val brace = skipWhitespace(source, index)
      if (brace < source.length && source[brace] == '{') {
        matchingBrace(source, brace)?.let { result += Block(brace, it, '{') }
      }
    }
    return result
  }

  private fun matching(source: String, openOffset: Int, open: Char, close: Char): Int? {
    if (openOffset !in source.indices || source[openOffset] != open) return null
    var depth = 0
    var index = openOffset
    while (index < source.length) {
      val next = skipIgnored(source, index)
      if (next != index) {
        index = next
        continue
      }
      when (source[index]) {
        open -> depth++
        close -> {
          depth--
          if (depth == 0) return index
        }
      }
      index++
    }
    return null
  }

  private fun nextCodeToken(source: String, start: Int): Pair<String, Int>? {
    var index = skipIgnored(source, start)
    if (index >= source.length) return null
    if (!source[index].isLetter() && source[index] != '_') return "" to (index + 1)
    val begin = index++
    while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index++
    return source.substring(begin, index) to index
  }

  private fun skipWhitespace(source: String, start: Int): Int {
    var index = start
    while (index < source.length && source[index].isWhitespace()) index++
    return index
  }

  private fun skipIgnored(source: String, start: Int): Int {
    var index = skipWhitespace(source, start)
    if (index + 1 < source.length && source[index] == '/' && source[index + 1] == '/') {
      val newline = source.indexOf('\n', index + 2)
      return if (newline < 0) source.length else newline + 1
    }
    if (index + 1 < source.length && source[index] == '/' && source[index + 1] == '*') {
      val end = source.indexOf("*/", index + 2)
      return if (end < 0) source.length else end + 2
    }
    if (index < source.length && (source[index] == '\'' || source[index] == '"')) {
      val quote = source[index]
      val triple = index + 2 < source.length && source[index + 1] == quote && source[index + 2] == quote
      val marker = if (triple) "$quote$quote$quote" else quote.toString()
      var cursor = index + if (triple) 3 else 1
      while (cursor < source.length) {
        if (!triple && source[cursor] == '\\') {
          cursor += 2
          continue
        }
        if (source.startsWith(marker, cursor)) return cursor + marker.length
        cursor++
      }
      return source.length
    }
    return index
  }
}