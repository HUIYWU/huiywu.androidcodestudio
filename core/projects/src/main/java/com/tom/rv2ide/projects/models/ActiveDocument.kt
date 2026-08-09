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

package com.tom.rv2ide.projects.models

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.nio.file.Path
import java.time.Instant

/**
 * A document that is opened in the editor.
 *
 * @author Akash Yadav
 */
open class ActiveDocument(
    val file: Path,
    var version: Int,
    var modified: Instant,
    content: String = "",
    revision: Long = 0L,
) {

  var content: String = content
    internal set

  var revision: Long = revision
    internal set

  internal fun update(version: Int, modified: Instant, content: String, revision: Long) {
    synchronized(this) {
      this.version = version
      this.modified = modified
      this.content = content
      this.revision = revision
    }
  }

  fun snapshot(): ActiveDocumentSnapshot {
    return synchronized(this) {
      ActiveDocumentSnapshot(file, version, modified, content, revision)
    }
  }

  fun inputStream(): BufferedInputStream {
    return snapshot().content.byteInputStream().buffered()
  }

  fun reader(): BufferedReader {
    return snapshot().content.reader().buffered()
  }
}

/** One coherent immutable view of an active document. */
data class ActiveDocumentSnapshot(
    val file: Path,
    val version: Int,
    val modified: Instant,
    val content: String,
    val revision: Long,
)

/** Immutable document identity used to prove a single continuous text edit. */
data class DocumentSnapshotIdentity(
    val file: Path,
    val version: Int,
    val revision: Long,
)

/**
 * A verified replacement from one active-document snapshot to the immediately following snapshot.
 *
 * This is input metadata for future incremental analysis only. It must not be interpreted as a
 * compiler cache or as permission to mutate a stable semantic snapshot.
 */
data class OneHopDocumentEdit(
    val base: DocumentSnapshotIdentity,
    val target: DocumentSnapshotIdentity,
    val baseStartIndex: Int,
    val baseEndIndex: Int,
    val removedText: String,
    val replacementText: String,
    val kind: Kind,
) {
  enum class Kind {
    INSERT,
    DELETE,
    REPLACE,
  }
}
