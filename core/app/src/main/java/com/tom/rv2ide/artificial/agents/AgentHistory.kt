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
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agents

/**
 * Cross-request conversation history in the neutral [AgentMessage] form.
 *
 * Only completed turns are kept — the user message and the assistant's final answer — never
 * the tool traffic in between, so a follow-up request does not replay every file that was read.
 * Each turn also remembers the editor tab it was written with, and [snapshot] annotates that onto
 * the user message, so the model can tell which file an earlier message belonged to even after
 * the tab has changed. The cap is what the providers' previous per-provider lists used.
 *
 * Once the history outgrows the caller's character limit, the older turns are folded into
 * [summary] and the summary replaces them, so a long conversation keeps its earlier context
 * without resending it verbatim. [snapshot] injects the summary as a system message.
 */
class AgentHistory {
    private data class Turn(
        val user: String,
        val assistant: String,
        val openFile: String?
    )

    private val turns = mutableListOf<Turn>()

    /** What the older turns were folded into, or null while nothing has been compressed. */
    private var summary: String? = null

    fun clear() {
        turns.clear()
        summary = null
    }

    /** A copy, so callers cannot mutate the history by holding on to the list. */
    fun snapshot(): List<AgentMessage> = buildList {
        summary?.let { add(AgentMessage.System(SUMMARY_HEADER + "\n" + it)) }
        turns.forEach { turn ->
            add(AgentMessage.User(annotateOpenFile(turn.user, turn.openFile)))
            add(AgentMessage.Assistant(turn.assistant))
        }
    }

    fun recordTurn(user: String, assistant: String, openFile: String?) {
        turns.add(Turn(user, assistant, openFile))
        if (turns.size > MAX_TURNS) {
            turns.removeAt(0)
        }
    }

    /** What the history contributes to a request right now, summary and annotations included. */
    fun usedChars(): Int = snapshot().sumOf { it.content.length }

    /** Turns old enough to be folded; the most recent [KEEP_TURNS] always stay verbatim. */
    fun foldableTurns(): Int = (turns.size - KEEP_TURNS).coerceAtLeast(0)

    /**
     * What to summarize, or null when there is nothing to fold.
     *
     * [force] is the manual trigger: it folds the older turns whatever their size, while the
     * automatic trigger only acts once [usedChars] has outgrown [limitChars].
     */
    fun compressionPlan(limitChars: Int, force: Boolean = false): CompressionPlan? {
        val foldCount = foldableTurns()
        if (foldCount == 0) return null
        if (!force && usedChars() <= limitChars) return null

        val text = buildString {
            summary?.let {
                append("Previous summary:\n")
                append(it)
                append("\n\n")
            }
            repeat(foldCount) { index ->
                val turn = turns[index]
                append("USER: ")
                append(annotateOpenFile(turn.user, turn.openFile))
                append("\n\nASSISTANT: ")
                append(turn.assistant)
                append("\n\n")
            }
        }

        return CompressionPlan(text, foldCount)
    }

    /** Replaces the folded turns with [newSummary]; the summary accumulates across compressions. */
    fun applyCompression(newSummary: String, foldedTurns: Int) {
        summary = newSummary
        repeat(foldedTurns.coerceAtMost(turns.size)) {
            turns.removeAt(0)
        }
    }

    data class CompressionPlan(val text: String, val foldedTurns: Int)

    companion object {
        const val MAX_TURNS = 10

        /** Most recent turns that are never folded; they are what the next message is about. */
        const val KEEP_TURNS = 4

        const val SUMMARY_HEADER = "=== EARLIER CONVERSATION (COMPRESSED) ==="

        /** Instruction the provider is given when folding turns; see [AIAgent.summarize]. */
        val COMPRESSION_PROMPT = """
            You compress the earlier part of an ongoing coding conversation so that it still fits
            into a later request. Keep, in compact form: what the user asked for, the decisions
            that were made, every file path that was created or modified, the current state of the
            work, and anything still unresolved. Do not copy code. Write in the language of the
            conversation. Keep it under 2000 characters.
        """.trimIndent()
    }
}

/** Prefixes a user message with the editor tab it was written with, when one was open. */
fun annotateOpenFile(user: String, openFile: String?): String =
    if (openFile == null) user else "[Open in editor: $openFile]\n\n$user"
