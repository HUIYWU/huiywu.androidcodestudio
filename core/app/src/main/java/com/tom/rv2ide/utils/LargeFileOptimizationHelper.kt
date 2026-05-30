/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.utils

import android.content.Context
import com.tom.rv2ide.editor.ui.IDEEditor
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File
import org.slf4j.LoggerFactory

/** Applies editor-side safeguards for large files. */
class LargeFileOptimizationHelper(
    private val context: Context,
    private val editor: CodeEditor,
) {

    private val log = LoggerFactory.getLogger(LargeFileOptimizationHelper::class.java)

    companion object {
        const val LARGE_FILE_THRESHOLD = 15 * 1024L
        const val HUGE_FILE_THRESHOLD = 100 * 1024L
        const val MASSIVE_FILE_THRESHOLD = 1024 * 1024L
    }

    fun isLargeFile(file: File): Boolean {
        return file.length() > LARGE_FILE_THRESHOLD
    }

    fun applyLargeFileOptimizations(file: File) {
        if (!isLargeFile(file)) return

        log.info("Applying large file optimizations for: ${file.name} (${file.length()} bytes)")

        val props = editor.props
        props.cacheRenderNodeForLongLines = false
        props.maxIPCTextLength = 16384
        props.clipboardTextLengthLimit = 262144

        if (editor is IDEEditor) {
            editor.isEnsurePosAnimEnabled = false
            editor.setWordwrap(false)
        }

        when {
            file.length() > MASSIVE_FILE_THRESHOLD -> {
                props.stickyScroll = false
                props.highlightMatchingDelimiters = false
                editor.setHighlightBracketPair(false)
                log.info("Applied massive file optimizations for: ${file.name}")
            }
            file.length() > HUGE_FILE_THRESHOLD -> {
                props.stickyScrollMaxLines = 1
                log.info("Applied huge file optimizations for: ${file.name}")
            }
        }
    }

    fun release() {
        log.info("LargeFileOptimizationHelper resources released")
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes bytes"
        }
    }

    fun showLargeFileWarning(file: File, onContinue: () -> Unit) {
        val message = buildString {
            append("The file ${file.name} is large (${formatFileSize(file.length())}).\n\n")
            append("Some operations may be slower:\n")
            append("- Syntax highlighting\n")
            append("- Auto-completion\n")
            append("- Search operations\n\n")
            append("Do you want to continue?")
        }

        android.app.AlertDialog.Builder(context)
            .setTitle("Large File Detected")
            .setMessage(message)
            .setPositiveButton("Continue") { _, _ -> onContinue() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun shouldApplyOptimizations(file: File): Boolean {
        return file.length() > LARGE_FILE_THRESHOLD
    }

    fun getOptimizationSuggestions(file: File): List<String> {
        val suggestions = mutableListOf<String>()

        if (file.length() > MASSIVE_FILE_THRESHOLD) {
            suggestions.add("Consider splitting this very large file")
            suggestions.add("Some features may be disabled for performance")
        } else if (file.length() > HUGE_FILE_THRESHOLD) {
            suggestions.add("Large file detected - performance optimizations applied")
        } else if (file.length() > LARGE_FILE_THRESHOLD) {
            suggestions.add("Moderate file size - basic optimizations applied")
        }

        return suggestions
    }
}
