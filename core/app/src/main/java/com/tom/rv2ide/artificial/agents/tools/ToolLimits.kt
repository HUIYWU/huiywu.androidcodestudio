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

/** Limits shared by the file tools. */
object ToolLimits {

    /** Characters a single read returns; longer content is truncated with a note. */
    const val MAX_FILE_READ_CHARS = 32_000

    /** Lines read_file_part returns when the caller gives no range. */
    const val DEFAULT_FILE_READ_PART_LINES = 200

    /** Characters a finding-type result (listing, search, file names) may carry. */
    const val MAX_TEXT_RESULT_LENGTH = 5_000
}