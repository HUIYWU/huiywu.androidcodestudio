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

package com.tom.rv2ide.eventbus.events.project

import com.tom.rv2ide.eventbus.events.Event

/**
 * Event dispatched when the project is initialized. A project instance is provided with this event
 * which can be obtained using [Event.get].
 *
 * @author Akash Yadav
 */
class ProjectInitializedEvent : Event()

/**
 * Dispatched after a module resource table refresh has completed and remains current.
 *
 * Consumers may re-evaluate diagnostics or presentation derived from resource resolution. The event
 * deliberately carries no resource contents or table instance; the registry remains the sole source
 * of resource semantics.
 */
data class ResourceTableRefreshedEvent(
    val modulePath: String,
    val immediate: Boolean,
)
