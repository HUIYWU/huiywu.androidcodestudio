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

package com.tom.rv2ide.fragments.sidebar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.Agents

/**
 * Activity-scoped ViewModel that owns the long-lived AI state.
 *
 * The AI fragments are hosted inside a [androidx.viewpager2.adapter.FragmentStateAdapter]
 * and hosted in the editor sidebar. Because Android may recreate the hosting Activity
 * (configuration change such as a dark/light theme switch, or process death while in the
 * background), every fragment MUST be reconstructible by the framework via a no-arg
 * constructor. Injecting the [AIAgentManager] through fragment constructors breaks that
 * contract and crashes on recreation.
 *
 * Holding the shared AI state in an [AndroidViewModel] keeps a single [AIAgentManager]
 * instance alive across those recreations and lets all AI fragments share it without
 * relying on their constructors.
 */
class AISharedViewModel(app: Application) : AndroidViewModel(app) {

    val aiAgent: AIAgentManager by lazy { AIAgentManager(getApplication()) }

    val agents: Agents by lazy { Agents(getApplication()) }
}