/*
 * This file is part of AndroidCodeStudio.
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