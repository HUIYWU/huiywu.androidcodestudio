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

package com.tom.rv2ide.artificial.render

import android.content.Context
import android.content.res.Configuration
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import com.google.android.material.color.MaterialColors
import com.tom.rv2ide.R
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.EditList
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator

/**
 * Renders the difference between a file's session baseline and what the agent wrote.
 *
 * The line rendering is hand-rolled rather than delegated to JGit's `DiffFormatter`: that class writes
 * bare unified-diff text with no line numbers and a flat colour, and the hunk headers and
 * "\ No newline at end of file" markers have to follow the theme like the rest of the row does.
 * JGit is still the one computing the edits — [DiffAlgorithm] plus [RawText] work entirely in memory,
 * with no `Repository` involved.
 */
class FileDiffRenderer private constructor() {

    data class Rendered(val added: Int, val removed: Int, val text: CharSequence)

    fun render(
        context: Context,
        baselineContent: String?,
        newContent: String
    ): Rendered {
        val before = RawText((baselineContent ?: "").toByteArray(Charsets.UTF_8))
        val after = RawText(newContent.toByteArray(Charsets.UTF_8))

        val edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS)
            .diff(RawTextComparator.DEFAULT, before, after)

        var added = 0
        var removed = 0
        for (edit in edits) {
            removed += edit.lengthA
            added += edit.lengthB
        }

        val palette = Palette(context)
        val builder = SpannableStringBuilder()
        val emitter = Emitter(builder, palette, maxOf(before.size(), after.size()))

        emitHunks(edits, before, after, emitter)

        if (emitter.truncated > 0) {
            emitter.notice(context.getString(R.string.chat_diff_truncated, emitter.truncated))
        }

        return Rendered(added, removed, builder)
    }

    /**
     * Walks [edits] as hunks of context lines, mirroring `DiffFormatter`'s grouping rule so the output
     * looks like the unified diff people already read. Edits closer than twice the context are merged
     * into one hunk, which is what stops a heavily rewritten file from becoming one header per line.
     */
    private fun emitHunks(
        edits: EditList,
        before: RawText,
        after: RawText,
        emitter: Emitter
    ) {
        val context = CONTEXT_LINES
        var index = 0

        // Walks every hunk even after the cap is reached: [Emitter.line] does the accounting, so
        // stopping early here would under-report how much was left out.
        while (index < edits.size) {
            var groupEnd = index + 1
            while (groupEnd < edits.size &&
                (edits[groupEnd].beginA - edits[groupEnd - 1].endA <= 2 * context ||
                    edits[groupEnd].beginB - edits[groupEnd - 1].endB <= 2 * context)
            ) {
                groupEnd++
            }
            val last = groupEnd - 1

            var a = maxOf(0, edits[index].beginA - context)
            var b = maxOf(0, edits[index].beginB - context)
            val aEnd = minOf(before.size(), edits[last].endA + context)
            val bEnd = minOf(after.size(), edits[last].endB + context)

            emitter.header(a, aEnd, b, bEnd)

            for (k in index..last) {
                val edit = edits[k]
                while (a < edit.beginA && a < aEnd) {
                    emitter.context(a, b, before)
                    a++
                    b++
                }
                while (a < edit.endA) {
                    emitter.removed(a, before)
                    a++
                }
                while (b < edit.endB) {
                    emitter.added(b, after)
                    b++
                }
            }
            while (a < aEnd && b < bEnd) {
                emitter.context(a, b, before)
                a++
                b++
            }

            index = groupEnd
        }
    }

    private class Palette(context: Context) {

        private val dark: Boolean =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        // Brighter than a de-saturated pastel, because the sidebar's surface is dim in dark mode and
        // the row's own text competes with the git-status icon next to it.
        val addedText: Int = if (dark) 0xFF7EE787.toInt() else 0xFF0A6E31.toInt()
        val removedText: Int = if (dark) 0xFFFF7B72.toInt() else 0xFFB3261E.toInt()
        val addedBackground: Int = if (dark) 0x3D7EE787 else 0x1F2DA44E
        val removedBackground: Int = if (dark) 0x3DFF7B72 else 0x1FCF222E

        // Gutter numbers and hunk headers follow the theme instead of carrying their own greys, so
        // they stay legible when the user switches between the light and dark IDE themes.
        val gutter: Int =
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        val header: Int = MaterialColors.getColor(context, android.R.attr.colorPrimary, 0)
    }

    private class Emitter(
        private val builder: SpannableStringBuilder,
        private val palette: Palette,
        lineCount: Int
    ) {
        private val numberWidth = lineCount.toString().length

        var emitted = 0
            private set
        var truncated = 0
            private set

        val full: Boolean get() = emitted >= MAX_RENDERED_LINES

        fun header(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int) {
            line(
                null,
                null,
                "@@ -${range(aStart, aEnd)} +${range(bStart, bEnd)} @@",
                palette.header,
                null
            )
        }

        fun context(a: Int, b: Int, before: RawText) {
            val text = before.getString(a).stripCarriageReturn()
            line(a + 1, b + 1, " $text", null, null)
            if (a + 1 == before.size() && before.isMissingNewlineAtEnd()) noNewline()
        }

        fun removed(a: Int, before: RawText) {
            val text = before.getString(a).stripCarriageReturn()
            line(a + 1, null, "-$text", palette.removedText, palette.removedBackground)
            if (a + 1 == before.size() && before.isMissingNewlineAtEnd()) noNewline()
        }

        fun added(b: Int, after: RawText) {
            val text = after.getString(b).stripCarriageReturn()
            line(null, b + 1, "+$text", palette.addedText, palette.addedBackground)
            if (b + 1 == after.size() && after.isMissingNewlineAtEnd()) noNewline()
        }

        private fun noNewline() {
            line(null, null, NO_NEWLINE, palette.header, null)
        }

        /** Appends the "how much was left out" line past the cap, which [line] would suppress. */
        fun notice(text: String) {
            val start = builder.length
            builder.append(text).append('\n')
            builder.setSpan(
                ForegroundColorSpan(palette.header),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        private fun line(oldNumber: Int?, newNumber: Int?, text: String, textColor: Int?, background: Int?) {
            if (full) {
                truncated++
                return
            }
            emitted++

            val start = builder.length
            val gutter = gutter(oldNumber, newNumber)
            builder.append(gutter).append(text).append('\n')
            val gutterEnd = start + gutter.length
            val end = builder.length

            builder.setSpan(
                ForegroundColorSpan(palette.gutter),
                start,
                gutterEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            if (background != null) {
                builder.setSpan(
                    BackgroundColorSpan(background),
                    gutterEnd,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (textColor != null) {
                builder.setSpan(
                    ForegroundColorSpan(textColor),
                    gutterEnd,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        private fun gutter(oldNumber: Int?, newNumber: Int?): String {
            return cell(oldNumber) + " " + cell(newNumber) + " | "
        }

        private fun cell(number: Int?): String {
            val text = number?.toString() ?: ""
            return " ".repeat(numberWidth - text.length) + text
        }

        private fun range(start: Int, end: Int): String {
            val count = end - start
            return when (count) {
                0 -> "$start,0"
                1 -> "${start + 1}"
                else -> "${start + 1},$count"
            }
        }
    }

    companion object {
        private const val CONTEXT_LINES = 3
        private const val MAX_RENDERED_LINES = 400
        private const val NO_NEWLINE = "\\ No newline at end of file"

        @Volatile
        private var instance: FileDiffRenderer? = null

        fun shared(): FileDiffRenderer {
            return instance ?: synchronized(this) {
                instance ?: FileDiffRenderer().also { instance = it }
            }
        }
    }
}

private fun String.stripCarriageReturn(): String =
    if (endsWith("\r")) dropLast(1) else this
