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

import com.tom.rv2ide.artificial.file.FileWriteResult

sealed interface AgentSegment {
    data class Text(val markdown: String) : AgentSegment

    /**
     * Reasoning the provider reported alongside the reply.
     *
     * Always first: it is produced before the answer it led to, and the transcript renders it as a
     * collapsed block above the prose.
     */
    data class Thinking(val markdown: String) : AgentSegment

    data class FileChange(
        val filePath: String,
        val content: String,
        val previousContent: String?,
        val writeResult: FileWriteResult
    ) : AgentSegment
}
