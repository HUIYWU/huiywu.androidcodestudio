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
package com.tom.rv2ide.testing.tooling

import com.tom.rv2ide.tooling.api.IProject
import com.tom.rv2ide.tooling.api.IToolingApiServer
import com.tom.rv2ide.tooling.api.messages.result.InitializeResult

/** Resources owned by one initialized tooling-server test session. */
class ToolingApiTestScope internal constructor(
    val server: IToolingApiServer,
    private val project: IProject,
    val result: InitializeResult,
) {
  /**
   * Returns the client-side JSON-RPC project proxy registered by [ToolingApiLauncher].
   *
   * Do not obtain this through IToolingApiServer.getRootProject(): returning the server's concrete
   * ProjectImpl would make Gson serialize nested dynamic proxies. On JDK 17 that reaches Proxy#h
   * reflection and fails module-access checks. The launcher-provided proxy preserves RPC dispatch.
   */
  fun requireProject(): IProject = project
}
