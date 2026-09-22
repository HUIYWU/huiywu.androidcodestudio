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

package com.tom.rv2ide.artificial.project.awareness

import com.tom.rv2ide.projects.IProjectManager
import java.io.File

/**
 * Project context for the agent: the root path, the modules the build
 * includes and one hint line. Anything deeper is fetched through the
 * file tools instead of being preloaded.
 */
class ProjectData {

    fun showProjectTree(proj: File): ProjectTreeResult {
        val root = proj.absolutePath
        val sb = StringBuilder()
        sb.appendLine(root)
        val modules = IProjectManager.getInstance().getWorkspace()
            ?.getSubProjects().orEmpty()
        for (module in modules.sortedBy { it.path }) {
            val directory = module.projectDir.absolutePath
            val shown = directory.removePrefix(root).trimStart('/')
                .ifEmpty { directory }
            sb.appendLine("${module.path} — $shown")
        }
        sb.appendLine()
        sb.appendLine(EXPLORE_HINT)
        return ProjectTreeResult(sb.toString())
    }

    private companion object {
        const val EXPLORE_HINT =
            "Explore with list_files / find_files / search; " +
            "hidden directories and build outputs are not sources."
    }
}

class ProjectTreeResult(val tree: String)
