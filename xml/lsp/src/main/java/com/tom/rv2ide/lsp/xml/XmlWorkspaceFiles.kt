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
package com.tom.rv2ide.lsp.xml

import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.utils.DocumentUtils
import java.nio.file.Path

/**
 * XML editor features are limited to files owned by the current workspace model.
 *
 * Definition targets may be AAR or SDK files, but those are read-only dependency sources: parsing
 * them for completion or diagnostics provides no editing value and can produce misleading errors.
 */
internal fun isWorkspaceXmlFile(file: Path): Boolean {
  return isWorkspaceXmlFile(DocumentUtils.isXmlFile(file)) {
    IProjectManager.getInstance().getWorkspace()?.findModuleForFile(file, false) != null
  }
}

internal fun isWorkspaceXmlFile(
    isXml: Boolean,
    hasOwningModule: () -> Boolean,
): Boolean {
  return isXml && hasOwningModule()
}