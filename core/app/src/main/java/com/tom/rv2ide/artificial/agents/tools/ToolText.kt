/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agents.tools

/**
 * Text shaping shared by the file tools: line-numbered output and bounded results.
 */

/** Renders [content] with right-aligned line numbers, starting at [startLine]. */
fun addLineNumbers(content: String, startLine: Int = 1): String {
    if (content.isEmpty()) return ""
    val lines = content.lines()
    val maxDigits = (startLine + lines.size - 1).toString().length
    return lines.mapIndexed { index, line ->
        "${(startLine + index).toString().padStart(maxDigits, ' ')}| $line"
    }.joinToString("\n")
}

/**
 * Removes the prefixes [addLineNumbers] produces.
 *
 * Used when matching `edit_file`'s `old` text: a caller may paste numbered output back, and the
 * prefixes are not part of the file.
 */
fun stripLineNumberPrefixes(text: String): String = text.replace(LINE_PREFIX, "")

private val LINE_PREFIX = Regex("^ *\\d+\\| ", RegexOption.MULTILINE)

/** Bounds [text] to [max] characters, appending [note] as the truncation marker. */
fun truncateWithNote(text: String, max: Int, note: String): String =
    if (text.length <= max) text else text.take(max) + "\n\n[" + note + "]"