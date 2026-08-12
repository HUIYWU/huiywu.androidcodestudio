package com.tom.rv2ide.lsp.java.models

import java.nio.file.Path

/**
 * Exact document snapshot associated with a full compilation request.
 *
 * Unlike [MethodReparsePlan], this state does not request incremental mutation. It only lets a
 * freshly compiled task retain the identity of the source snapshot it was built from, so a later
 * strictly contiguous Completion request can safely attempt method-level reparse.
 */
data class CompilationDocumentState(
    @JvmField val file: Path,
    @JvmField val contents: String,
    @JvmField val documentVersion: Int,
    @JvmField val documentRevision: Long,
)
