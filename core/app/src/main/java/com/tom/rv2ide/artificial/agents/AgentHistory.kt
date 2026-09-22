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
 */
class AgentHistory {
    private data class Turn(
        val user: String,
        val assistant: String,
        val openFile: String?
    )

    private val turns = mutableListOf<Turn>()

    fun clear() {
        turns.clear()
    }

    /** A copy, so callers cannot mutate the history by holding on to the list. */
    fun snapshot(): List<AgentMessage> = turns.flatMap { turn ->
        listOf(
            AgentMessage.User(annotateOpenFile(turn.user, turn.openFile)),
            AgentMessage.Assistant(turn.assistant)
        )
    }

    fun recordTurn(user: String, assistant: String, openFile: String?) {
        turns.add(Turn(user, assistant, openFile))
        if (turns.size > MAX_TURNS) {
            turns.removeAt(0)
        }
    }

    private companion object {
        const val MAX_TURNS = 10
    }
}

/** Prefixes a user message with the editor tab it was written with, when one was open. */
fun annotateOpenFile(user: String, openFile: String?): String =
    if (openFile == null) user else "[Open in editor: $openFile]\n\n$user"
