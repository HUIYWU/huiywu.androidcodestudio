package com.tom.rv2ide.projects.gradleedit

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Reversible transaction for a bounded set of project-file edits and directory moves/creations
 * performed by one module-management operation.
 *
 * Callers must register files before writing them and directories only while they do not exist.
 * Rollback restores captured files, moves registered directories back in reverse order, then
 * removes transaction-created parent directories from deepest to shallowest.
 */
class ProjectEditTransaction private constructor(private val allowedRoots: List<File>) {
  private data class FileSnapshot(val file: File, val contents: ByteArray)

  private val snapshots = linkedMapOf<File, FileSnapshot>()
  private val createdDirectories = linkedMapOf<File, Boolean>()
  private val movedDirectories = mutableListOf<Pair<File, File>>()
  private var completed = false

  fun capture(file: File) {
    checkActive()
    val normalized = normalizeInsideRoot(file)
    if (!normalized.isFile) throw IOException("Cannot snapshot missing file: ${normalized.path}")
    snapshots.putIfAbsent(normalized, FileSnapshot(normalized, normalized.readBytes()))
  }

  fun captureAll(files: Iterable<File>) = files.forEach(::capture)

  /** Register an empty directory which does not exist yet; rollback removes it only if it remains empty. */
  fun trackCreatedParentDirectory(directory: File) = trackCreatedDirectory(directory, recursiveDelete = false)

  /** Register a module/output directory which does not exist yet; rollback may remove its contents. */
  fun trackCreatedDirectory(directory: File) = trackCreatedDirectory(directory, recursiveDelete = true)

  private fun trackCreatedDirectory(directory: File, recursiveDelete: Boolean) {
    checkActive()
    val normalized = normalizeInsideRoot(directory)
    if (normalized.exists()) throw IOException("Cannot track existing directory: ${normalized.path}")
    createdDirectories[normalized] = recursiveDelete
  }

  fun moveDirectory(from: File, to: File) {
    checkActive()
    val source = normalizeInsideRoot(from)
    val destination = normalizeInsideRoot(to)
    if (!source.isDirectory) throw IOException("Cannot move missing directory: ${source.path}")
    if (destination.exists()) throw IOException("Cannot move directory onto an existing destination: ${destination.path}")
    if (destination.toPath().startsWith(source.toPath())) throw IOException("Cannot move a directory inside itself: ${destination.path}")
    missingParents(destination.parentFile).forEach(::trackCreatedParentDirectory)
    destination.parentFile?.mkdirs()
    try {
      Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
      Files.move(source.toPath(), destination.toPath())
    }
    movedDirectories += source to destination
  }

  fun applyTextEdit(file: File, source: String, result: GradleEditResult, description: String) {
    applyTextEditInternal(file, source, result, description, allowNoChange = true)
  }

  /** Applies an edit that was proven applicable during planning. NoChange is an execution error. */
  fun applyRequiredTextEdit(file: File, source: String, result: GradleEditResult, description: String) {
    applyTextEditInternal(file, source, result, description, allowNoChange = false)
  }

  private fun applyTextEditInternal(
      file: File,
      source: String,
      result: GradleEditResult,
      description: String,
      allowNoChange: Boolean,
  ) {
    checkActive()
    val normalized = normalizeInsideRoot(file)
    capture(normalized)
    when (result) {
      is GradleEditResult.Applied -> normalized.writeText(TextEditApplier.apply(source, result.edits))
      GradleEditResult.NoChange -> if (!allowNoChange) throw IOException("Cannot edit $description: target was not found")
      is GradleEditResult.Unsupported -> throw IOException("Cannot edit $description: ${result.reason}")
      is GradleEditResult.Ambiguous -> throw IOException("Cannot edit $description: ${result.reason}")
      is GradleEditResult.Invalid -> throw IOException("Cannot edit $description: ${result.reason}")
    }
  }

  fun commit() {
    checkActive()
    completed = true
    snapshots.clear()
    createdDirectories.clear()
    movedDirectories.clear()
  }

  /** Attempts every restoration/cleanup action and returns errors instead of hiding later work. */
  fun rollback(): List<Throwable> {
    if (completed) return emptyList()
    val failures = mutableListOf<Throwable>()
    snapshots.values.forEach { snapshot ->
      runCatching { snapshot.file.writeBytes(snapshot.contents) }.exceptionOrNull()?.let(failures::add)
    }
    movedDirectories.asReversed().forEach { (source, destination) ->
      runCatching {
        if (destination.isDirectory && !source.exists()) {
          try {
            Files.move(destination.toPath(), source.toPath(), StandardCopyOption.ATOMIC_MOVE)
          } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(destination.toPath(), source.toPath())
          }
        }
      }.exceptionOrNull()?.let(failures::add)
    }
    createdDirectories.entries.sortedByDescending { it.key.path.length }.forEach { (directory, recursiveDelete) ->
      val removed = when {
        !directory.exists() -> true
        recursiveDelete -> directory.deleteRecursively()
        directory.isDirectory && directory.list().isNullOrEmpty() -> directory.delete()
        else -> true
      }
      if (!removed) failures += IOException("Could not remove transaction-created directory: ${directory.path}")
    }
    completed = true
    snapshots.clear()
    movedDirectories.clear()
    createdDirectories.clear()
    return failures
  }

  private fun missingParents(directory: File?): List<File> {
    val parents = mutableListOf<File>()
    var current = directory
    while (current != null && !current.exists()) {
      parents += current
      current = current.parentFile
    }
    return parents.asReversed()
  }

  private fun checkActive() {
    check(!completed) { "Project edit transaction is already complete" }
  }

  private fun normalizeInsideRoot(file: File): File {
    val normalized = file.canonicalFile
    if (allowedRoots.none { root -> normalized == root || normalized.toPath().startsWith(root.toPath()) }) {
      throw IOException("Path is outside permitted project roots: ${file.path}")
    }
    return normalized
  }

  companion object {
    /** Allows the workspace root plus known, Tooling-validated external module directories. */
    fun begin(projectRoot: File, additionalAllowedRoots: Iterable<File> = emptyList()): ProjectEditTransaction {
      if (!projectRoot.isDirectory) throw IOException("Project root directory does not exist: ${projectRoot.path}")
      val roots = buildList {
        add(projectRoot.canonicalFile)
        additionalAllowedRoots.forEach { root ->
          if (!root.isDirectory) throw IOException("Allowed project directory does not exist: ${root.path}")
          add(root.canonicalFile)
        }
      }.distinct()
      return ProjectEditTransaction(roots)
    }
  }
}