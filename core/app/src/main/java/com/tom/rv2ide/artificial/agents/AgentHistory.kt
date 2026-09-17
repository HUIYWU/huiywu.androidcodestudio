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
 * Only completed turns are kept — the user message and the assistant's final answer — never the
 * tool traffic in between, so a follow-up request does not replay every file that was read. The cap
 * is what the providers' previous per-provider lists used.
 */
class AgentHistory {
    private val entries = mutableListOf<AgentMessage>()

    fun clear() {
        entries.clear()
    }

    /** A copy, so callers cannot mutate the history by holding on to the list. */
    fun snapshot(): List<AgentMessage> = entries.toList()

    fun recordTurn(user: String, assistant: String) {
        entries.add(AgentMessage.User(user))
        entries.add(AgentMessage.Assistant(assistant))
        if (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
            entries.removeAt(0)
        }
    }

    private companion object {
        const val MAX_ENTRIES = 20
    }
}
