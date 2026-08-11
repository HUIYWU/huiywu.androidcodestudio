package com.tom.rv2ide.lsp.java.models

import java.nio.file.Path

/**
 * Immutable snapshot used by the method-level reparse MVP.
 *
 * The contents are the exact text that will be analyzed by javac, after CompletionProvider's
 * temporary syntax fixes have been applied. The compiler derives the single edit range by comparing
 * this snapshot with the text retained by the cached CompileBatch.
 */
data class MethodReparsePlan(
    @JvmField val file: Path,
    @JvmField val contents: String,
    @JvmField val documentVersion: Int,
    @JvmField val documentRevision: Long,
    @JvmField val cursor: Long,
)