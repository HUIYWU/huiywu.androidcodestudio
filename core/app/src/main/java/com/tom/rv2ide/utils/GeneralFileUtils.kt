/*
 *  This file is part of Android Code Studio.
 *
 *  Android Code Studio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Android Code Studio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with Android Code Studio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.utils

/** * @Author Tom */
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.slf4j.LoggerFactory

class GeneralFileUtils {
  companion object {

    private val log = LoggerFactory.getLogger(GeneralFileUtils::class.java)

    /**
     * Checks if the file exists. Returns false if file is null. Ensure your project includes the
     * Kotlin standard library dependency.
     */
    fun isFileExists(file: File?): Boolean {
      return file?.exists() ?: false
    }

    /** * List files in a dir */
    fun listFilesInDirectory(directory: File?): List<File> {
      return directory?.listFiles()?.toList() ?: emptyList()
    }

    /** * List directories in a dir */
    fun listDirsInDirectory(directory: File?): List<File> {
      return directory?.listFiles()?.filter { it.isDirectory } ?: emptyList()
    }

    /**
     * Returns whether renaming [file] to [newName] would change nothing but the case of its name.
     *
     * Such a rename is a special case: the IDE works on shared storage, which is a case-insensitive
     * file system. There the new path resolves to the entry being renamed, so the target is *not*
     * another file that happens to exist, and it cannot be renamed with a single rename either.
     */
    private fun isCaseOnlyRename(file: File, newName: String): Boolean {
      return newName != file.name && newName.equals(file.name, ignoreCase = true)
    }

    /**
     * Renames [file] to [newName], keeping it in the same directory.
     *
     * A plain [File.renameTo] is not enough here. The IDE works on shared storage, which is a
     * case-insensitive file system, and there a rename that only changes the case is treated as a
     * rename onto itself: it is silently accepted without changing the name, so the caller would
     * report success while nothing actually happened. Such renames are handled by [renameCaseOnly].
     *
     * @return `true` if the file now has the requested name.
     */
    fun renameFile(file: File, newName: String): Boolean {
      val name = newName.trim()
      if (name.isEmpty()) return false

      // Renaming to the identical name is a no-op, reported as success to match FileUtils.rename().
      if (name == file.name) return true
      if (!file.exists()) return false

      val parent = file.parentFile ?: return false
      val target = File(parent, name)

      if (isCaseOnlyRename(file, name)) {
        return renameCaseOnly(file, target, parent)
      }

      // Refuse to overwrite an existing entry, so the failure stays explicit instead of letting
      // renameTo() silently replace the target on some file systems.
      if (target.exists()) return false
      return file.renameTo(target)
    }

    /**
     * Performs a rename which only changes the case of [file].
     *
     * The target path is ambiguous here, so it has to be resolved before anything is moved:
     *
     * - On a case-insensitive file system (shared storage) it resolves to [file] itself. The rename is
     *   then done through a temporary name, because a direct rename is treated as a rename onto itself
     *   and silently leaves the name unchanged.
     * - On a case-sensitive file system the target may be a genuinely different entry. Renaming onto it
     *   would destroy that entry, so the rename is refused instead.
     */
    private fun renameCaseOnly(file: File, target: File, parent: File): Boolean {
      if (!target.exists()) {
        // Case-sensitive file system and nothing to collide with: an ordinary rename is enough.
        return file.renameTo(target)
      }

      if (!isSameEntry(file, target)) {
        return false
      }

      return renameViaTemporaryName(file, target, parent)
    }

    /**
     * Returns whether [first] and [second] denote the same entry on disk.
     *
     * Compares the two paths through the file system rather than by their spelling, which is what
     * distinguishes "the target is the file being renamed" from "the target is another file whose name
     * differs only in case".
     */
    private fun isSameEntry(first: File, second: File): Boolean {
      return try {
        Files.isSameFile(first.toPath(), second.toPath())
      } catch (err: IOException) {
        // Thrown when the target cannot be resolved, in which case it is not the same entry.
        log.debug("Unable to compare {} with {}", first.absolutePath, second.absolutePath, err)
        false
      } catch (err: SecurityException) {
        log.debug("Unable to compare {} with {}", first.absolutePath, second.absolutePath, err)
        false
      }
    }

    /**
     * Renames [file] to [target] through a temporary name inside [parent].
     *
     * The first step moves the entry to a name that differs from both [file] and [target] by more than
     * case, which is what turns the second step into an ordinary rename. If the second step fails the
     * first one is rolled back, so the caller never has to deal with a file left under the temporary
     * name.
     */
    private fun renameViaTemporaryName(file: File, target: File, parent: File): Boolean {
      val temporary = createTemporarySibling(file, parent)
      if (!file.renameTo(temporary)) return false

      if (temporary.renameTo(target)) return true

      if (!temporary.renameTo(file)) {
        log.error(
            "Failed to rename {} back to {} after an unsuccessful case-only rename",
            temporary.absolutePath,
            file.absolutePath,
        )
      }
      return false
    }

    /** Returns a sibling of [file] whose name cannot collide with [file] on any file system. */
    private fun createTemporarySibling(file: File, parent: File): File {
      var counter = 0
      while (true) {
        val candidate = File(parent, "${file.name}.acs-rename-${System.nanoTime()}-$counter.tmp")
        if (!candidate.exists()) return candidate
        counter++
      }
    }
  }
}
