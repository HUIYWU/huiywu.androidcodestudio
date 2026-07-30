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
package com.tom.rv2ide.lsp.xml.actions

import android.graphics.drawable.Drawable
import com.android.aaptcompiler.AaptResourceType.ATTR
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.ActionItem
import com.tom.rv2ide.actions.EditorActionItem
import com.tom.rv2ide.actions.hasRequiredData
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.lsp.api.ILanguageServerRegistry
import com.tom.rv2ide.lsp.models.CodeActionItem
import com.tom.rv2ide.lsp.models.CodeActionKind
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DocumentChange
import com.tom.rv2ide.lsp.models.PerformCodeActionParams
import com.tom.rv2ide.lsp.models.TextEdit
import com.tom.rv2ide.lsp.xml.XMLLanguageServer
import com.tom.rv2ide.lsp.xml.isWorkspaceXmlFile
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.xml.resources.ResourceTableRegistry
import java.io.File

/** Offers a conservative spelling correction for an AXML002 framework attribute diagnostic. */
internal class CorrectAttributeNameAction : EditorActionItem {
  override val id: String = "ide.editor.lsp.xml.correctAttributeName"
  override var label: String = ""
  override var visible: Boolean = true
  override var enabled: Boolean = true
  override var icon: Drawable? = null

  override var requiresUIThread: Boolean = false
  override var location: ActionItem.Location = ActionItem.Location.EDITOR_CODE_ACTIONS

  private var replacement: String? = null

  override fun prepare(data: ActionData) {
    super.prepare(data)
    replacement = null
    if (!data.hasRequiredData(File::class.java, DiagnosticItem::class.java)) {
      markInvisible()
      return
    }

    val file = data[File::class.java]!!
    val diagnostic = data[DiagnosticItem::class.java]!!
    if (!isWorkspaceXmlFile(file.toPath()) || diagnostic.code != CODE_UNKNOWN_LAYOUT_ATTRIBUTE) {
      markInvisible()
      return
    }

    val attributeName = attributeNameFromDiagnostic(diagnostic.message) ?: run {
      markInvisible()
      return
    }
    val localName = attributeName.removePrefix(ANDROID_ATTRIBUTE_PREFIX)
    val candidate =
        findUniqueSuggestion(localName, frameworkAttributeNames()) ?: run {
          markInvisible()
          return
        }

    replacement = "$ANDROID_ATTRIBUTE_PREFIX$candidate"
    label = "Change to $replacement"
    visible = true
    enabled = true
  }

  override suspend fun execAction(data: ActionData): Any {
    val diagnostic = data[DiagnosticItem::class.java] ?: return false
    val newName = replacement ?: return false
    val file = data[File::class.java] ?: return false
    val server = ILanguageServerRegistry.getDefault().getServer(XMLLanguageServer.SERVER_ID)
        as? XMLLanguageServer
        ?: return false
    val client = server.client ?: return false

    val action =
        CodeActionItem().apply {
          title = label
          kind = CodeActionKind.QuickFix
          changes = listOf(DocumentChange(file.toPath(), listOf(TextEdit(diagnostic.range, newName))))
        }
    client.performCodeAction(PerformCodeActionParams(async = false, action = action))
    return true
  }

  private fun frameworkAttributeNames(): Set<String> {
    return Lookup.getDefault()
        .lookup(ResourceTableRegistry.COMPLETION_FRAMEWORK_RES)
        ?.findPackage(ResourceTableRegistry.PCK_ANDROID)
        ?.findGroup(ATTR)
        ?.findEntries { true }
        ?.map { it.name }
        ?.toSet()
        .orEmpty()
  }

  internal companion object {
    const val CODE_UNKNOWN_LAYOUT_ATTRIBUTE = "AXML002"
    private const val ANDROID_ATTRIBUTE_PREFIX = "android:"
    private const val MAX_EDIT_DISTANCE = 2
    private val ATTRIBUTE_MESSAGE = Regex("^Unknown attribute '(android:[A-Za-z_][A-Za-z0-9_]*)'.*$" )

    internal fun attributeNameFromDiagnostic(message: String): String? {
      return ATTRIBUTE_MESSAGE.matchEntire(message)?.groupValues?.getOrNull(1)
    }

    /**
     * Only a single close match is safe to expose as an automatic edit. Ties deliberately produce
     * no action, because a menu fix must not replace a valid intended spelling by guesswork.
     */
    internal fun findUniqueSuggestion(name: String, candidates: Collection<String>): String? {
      val matches =
          candidates
              .asSequence()
              .map { candidate -> candidate to editDistance(name, candidate) }
              .filter { (_, distance) -> distance in 1..MAX_EDIT_DISTANCE }
              .toList()
      val bestDistance = matches.minOfOrNull { it.second } ?: return null
      val best = matches.filter { it.second == bestDistance }.map { it.first }
      return best.singleOrNull()
    }

    internal fun editDistance(first: String, second: String): Int {
      var previous = IntArray(second.length + 1) { it }
      for (firstIndex in first.indices) {
        val current = IntArray(second.length + 1)
        current[0] = firstIndex + 1
        for (secondIndex in second.indices) {
          current[secondIndex + 1] =
              minOf(
                  current[secondIndex] + 1,
                  previous[secondIndex + 1] + 1,
                  previous[secondIndex] + if (first[firstIndex] == second[secondIndex]) 0 else 1,
              )
        }
        previous = current
      }
      return previous[second.length]
    }
  }
}
