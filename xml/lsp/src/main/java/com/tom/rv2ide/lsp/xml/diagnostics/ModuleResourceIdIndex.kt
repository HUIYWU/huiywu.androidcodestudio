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
package com.tom.rv2ide.lsp.xml.diagnostics

import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.eclipse.lemminx.dom.DOMElement
import org.eclipse.lemminx.dom.DOMNode
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager

/** Cached compile-scope index of IDs declared by source resource XML files. */
internal object ModuleResourceIdIndex {
  private val caches = ConcurrentHashMap<String, ModuleCache>()

  fun snapshot(currentFile: Path, currentText: String): Snapshot {
    val module =
        Lookup.getDefault().lookup(ModuleProject.COMPLETION_MODULE_KEY) as? AndroidModule
            ?: return Snapshot.Unavailable
    val resourceDirectories = module.getResourceDirectories().map { it.toPath().normalize() }.toSet()
    if (resourceDirectories.isEmpty()) {
      return Snapshot.Unavailable
    }

    val previous = caches[module.path]
    val now = System.currentTimeMillis()
    val cache =
        if (previous != null &&
            previous.directories == resourceDirectories &&
            now - previous.refreshedAtMillis < DISK_REFRESH_INTERVAL_MILLIS) {
          previous
        } else {
          refreshDisk(resourceDirectories, previous, now) ?: return Snapshot.Unavailable
        }
    caches[module.path] = cache
    val idsByFile = cache.idsByFile.toMutableMap()
    val activeResourceFiles =
        FileManager.getActiveDocumentFiles().filter { file ->
          file.toString().endsWith(XML_SUFFIX) &&
              resourceDirectories.any { directory -> file.normalize().startsWith(directory) }
        }
    for (file in activeResourceFiles) {
      val text = runCatching { FileManager.getDocumentContents(file) }.getOrNull()
          ?: return Snapshot.Unavailable
      val ids = collectIds(text) ?: return Snapshot.Unavailable
      idsByFile[file.normalize()] = ids
    }

    // The current diagnostic text is authoritative even if FileManager has not published the latest
    // change event yet.
    if (resourceDirectories.any { currentFile.normalize().startsWith(it) }) {
      idsByFile[currentFile.normalize()] = collectIds(currentText) ?: return Snapshot.Unavailable
    }
    return Snapshot.Available(idsByFile.values.flatten().toSet())
  }

  private fun refreshDisk(
      resourceDirectories: Set<Path>,
      previous: ModuleCache?,
      refreshedAtMillis: Long,
  ): ModuleCache? {
    val signatures = mutableMapOf<Path, FileSignature>()
    return runCatching {
          resourceDirectories.forEach { directory ->
            if (!Files.exists(directory)) {
              return@forEach
            }
            Files.walk(directory).use { paths ->
              paths
                  .filter { file -> Files.isRegularFile(file) && file.toString().endsWith(XML_SUFFIX) }
                  .forEach { file ->
                    val normalized = file.normalize()
                    signatures[normalized] =
                        FileSignature(
                            modifiedMillis = Files.getLastModifiedTime(file).toMillis(),
                            size = Files.size(file),
                        )
                  }
            }
          }

          val idsByFile = mutableMapOf<Path, Set<String>>()
          signatures.forEach { (file, signature) ->
            val cached =
                previous
                    ?.takeIf { it.directories == resourceDirectories }
                    ?.takeIf { it.signatures[file] == signature }
                    ?.idsByFile
                    ?.get(file)
            idsByFile[file] =
                cached
                    ?: collectIds(FileManager.getDocumentContents(file))
                    ?: error("Unable to parse $file")
          }
          ModuleCache(resourceDirectories, signatures, idsByFile, refreshedAtMillis)
        }
        .getOrNull()
  }

  internal fun collectIds(text: String): Set<String>? {
    val document =
        runCatching {
              DOMParser.getInstance()
                  .parse(text, ANDROID_NAMESPACE_URI, URIResolverExtensionManager())
            }
            .getOrNull() ?: return null
    if (hasSyntaxRecovery(document)) {
      return null
    }
    val result = collectLocalIdDeclarations(document).toMutableSet()

    fun collectValuesIds(node: DOMNode) {
      if (node is DOMElement && node.tagName == ITEM_TAG && node.getAttribute(TYPE_ATTRIBUTE) == ID_TYPE) {
        node.getAttribute(NAME_ATTRIBUTE)?.takeIf { it.isNotBlank() }?.let(result::add)
      }
      node.children.forEach(::collectValuesIds)
    }
    collectValuesIds(document)
    return result
  }

  private fun hasSyntaxRecovery(node: DOMNode): Boolean {
    if (node is DOMElement) {
      if (node.isOrphanEndTag ||
          (node.hasStartTag() &&
              !node.isSelfClosed &&
              (!node.isStartTagClosed || !node.isClosed))) {
        return true
      }
    }
    return node.children.any(::hasSyntaxRecovery)
  }

  internal sealed interface Snapshot {
    data class Available(val ids: Set<String>) : Snapshot
    data object Unavailable : Snapshot
  }

  private data class ModuleCache(
      val directories: Set<Path>,
      val signatures: Map<Path, FileSignature>,
      val idsByFile: Map<Path, Set<String>>,
      val refreshedAtMillis: Long,
  )

  private data class FileSignature(
      val modifiedMillis: Long,
      val size: Long,
  )

  private const val DISK_REFRESH_INTERVAL_MILLIS = 1_000L
  private const val XML_SUFFIX = ".xml"
  private const val ITEM_TAG = "item"
  private const val TYPE_ATTRIBUTE = "type"
  private const val NAME_ATTRIBUTE = "name"
  private const val ID_TYPE = "id"
  private const val ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android"
}