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

package com.tom.rv2ide.tooling.api.models

import java.io.File
import java.io.Serializable

/**
 * Formal descriptor for a composite build dependency module discovered by tooling.
 *
 * This descriptor intentionally stays metadata-only so tooling can report composite build
 * discoveries without mixing new project implementations into the existing project list.
 * Workspace can consume this list to build official module descriptors while keeping the
 * current fallback path as a compatibility backup.
 */
class CompositeBuildDescriptor(
    val name: String,
    val projectPath: String,
    val projectDir: File,
    val buildDir: File,
    val buildScript: File?,
    val sourceRoots: List<File>,
    val isHeavy: Boolean,
) : Serializable {

  private val serialVersionUID = 1L
  private val gsonType: String = javaClass.name
}
