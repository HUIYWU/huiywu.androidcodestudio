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
package com.tom.rv2ide.utils

import android.content.Context
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.createGraph
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.FragmentNavigatorDestinationBuilder
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.get
import androidx.navigation.navOptions
import androidx.recyclerview.widget.LinearLayoutManager
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.ActionItem
import com.tom.rv2ide.actions.ActionsRegistry
import com.tom.rv2ide.actions.SidebarActionItem
import com.tom.rv2ide.actions.internal.DefaultActionsRegistry
import com.tom.rv2ide.actions.sidebar.AIAgentSidebarAction
import com.tom.rv2ide.actions.sidebar.AssetStudioSidebarAction
import com.tom.rv2ide.actions.sidebar.BuildVariantsSidebarAction
import com.tom.rv2ide.actions.sidebar.CloseProjectSidebarAction
import com.tom.rv2ide.actions.sidebar.FileTreeSidebarAction
import com.tom.rv2ide.actions.sidebar.PreferencesSidebarAction
import com.tom.rv2ide.actions.sidebar.ModuleManagerSidebarAction
import com.tom.rv2ide.actions.sidebar.GitClientAction
import com.tom.rv2ide.actions.sidebar.TerminalSidebarAction
import com.tom.rv2ide.fragments.sidebar.EditorSidebarFragment
import androidx.fragment.app.Fragment
import java.lang.ref.WeakReference

internal object EditorSidebarActions {

  private val fragmentCache = mutableMapOf<String, Fragment>()

  @JvmStatic
  fun registerActions(context: Context) {
    val registry = ActionsRegistry.getInstance()
    var order = -1

    @Suppress("KotlinConstantConditions")
    registry.registerAction(FileTreeSidebarAction(context, ++order))
    registry.registerAction(BuildVariantsSidebarAction(context, ++order))
    registry.registerAction(GitClientAction(context, ++order))
    registry.registerAction(AIAgentSidebarAction(context, ++order))
    registry.registerAction(AssetStudioSidebarAction(context, ++order))
    registry.registerAction(ModuleManagerSidebarAction(context, ++order))
    registry.registerAction(PreferencesSidebarAction(context, ++order))
    registry.registerAction(CloseProjectSidebarAction(context, ++order))
  }

  @JvmStatic
  fun removeFragmentFromCache(fragmentId: String) {
    fragmentCache.remove(fragmentId)
  }

  @JvmStatic
  fun setup(sidebarFragment: EditorSidebarFragment) {
    val binding = sidebarFragment.getBinding() ?: return
    val context = sidebarFragment.requireContext()
    val navigationRecycler =
        binding.navigation.findViewById<androidx.recyclerview.widget.RecyclerView>(
            com.tom.rv2ide.R.id.navigation_recycler
        )

    val registry = ActionsRegistry.getInstance()
    val actions = registry.getActions(ActionItem.Location.EDITOR_SIDEBAR)
    if (actions.isEmpty()) {
      return
    }

    val data = ActionData()
    data.put(Context::class.java, context)

    val titleRef = WeakReference(binding.title)
    val subtitleRef = WeakReference(binding.subtitle)

    fun updateTitleVisibility(title: String?) {
      titleRef.get()?.let { titleView ->
        if (!title.isNullOrEmpty()) {
          titleView.text = title
          titleView.visibility = android.view.View.VISIBLE
        } else {
          titleView.visibility = android.view.View.GONE
        }
      }
    }

    fun updateSubtitleVisibility(subtitle: String?) {
      subtitleRef.get()?.let { subtitleView ->
        if (!subtitle.isNullOrEmpty()) {
          subtitleView.text = subtitle
          subtitleView.visibility = android.view.View.VISIBLE
        } else {
          subtitleView.visibility = android.view.View.GONE
        }
      }
    }

    val sortedActions =
        actions.entries.sortedBy { (_, action) ->
          (action as? SidebarActionItem)?.order ?: Int.MAX_VALUE
        }

    val navigationItems =
        sortedActions.map { (actionId, action) ->
          action as SidebarActionItem

          action.prepare(data)

          SidebarNavigationItem(
              id = actionId,
              icon = ContextCompat.getDrawable(context, action.iconRes),
              title = action.label,
              subtitle = action.subtitle,
              isSelected = actionId == FileTreeSidebarAction.ID,
              action = action,
          )
        }

    var currentFragmentId: String? = null
    lateinit var adapter: SidebarNavigationAdapter

    adapter = SidebarNavigationAdapter(
            onItemClick = { item ->
              val action = item.action

              if (action.fragmentClass == null) {
                (registry as DefaultActionsRegistry).executeAction(action, data)
                return@SidebarNavigationAdapter
              }

              if (currentFragmentId == action.id) {
                return@SidebarNavigationAdapter
              }

              // Prefer the instance restored by the FragmentManager (configuration change). It is
              // already attached to the container, so creating another instance from the cache would
              // stack a duplicate page on top of the restored one.
              val childFm = sidebarFragment.childFragmentManager
              val restored = childFm.findFragmentByTag(action.id)
              val fragment =
                  restored
                      ?: fragmentCache.getOrPut(action.id) {
                        action.fragmentClass!!.java.newInstance()
                      }

              if (restored != null) {
                fragmentCache[action.id] = restored
              }

              val transaction = childFm.beginTransaction()

              childFm.fragments.forEach { existingFragment ->
                if (existingFragment.isAdded) {
                  transaction.hide(existingFragment)
                }
              }

              if (fragment.isAdded) {
                transaction.show(fragment)
              } else {
                transaction.add(binding.fragmentContainer.id, fragment, action.id)
              }

              transaction.commitNow()

              currentFragmentId = action.id

              updateTitleVisibility(item.title)
              updateSubtitleVisibility(item.subtitle)

              val updatedItems =
                  navigationItems.map { navItem -> 
                    navItem.copy(isSelected = navItem.id == item.id) 
                  }
              adapter.submitList(updatedItems)
            },
            onItemLongClick = { item ->
              if (item.action is TerminalSidebarAction) {
                true
              } else {
                false
              }
            },
        )

    navigationRecycler.layoutManager =
        LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
    navigationRecycler.adapter = adapter
    adapter.submitList(navigationItems)

    // A configuration change (e.g. a light/dark mode switch) makes the FragmentManager
    // restore the pages that were previously added to this container. Adding the first page
    // again would stack a second, non-interactive copy on top of the restored one, which is
    // what produced the "ghost" file tree layer. So adopt the restored instances instead.
    val fragmentManager = sidebarFragment.childFragmentManager
    val knownPageIds = navigationItems.map { it.id }.toHashSet()
    val restoredPages =
        fragmentManager.fragments.filter { fragment ->
          // Fragments declared in the layout (e.g. the NavHostFragment placeholder) have no tag
          // and are not sidebar pages, so they must not be treated as restored pages.
          val tag = fragment.tag
          fragment.isAdded && tag != null && tag in knownPageIds
        }

    if (restoredPages.isNotEmpty()) {
      restoredPages.forEach { page -> fragmentCache[page.tag!!] = page }

      val visiblePage = restoredPages.lastOrNull { !it.isHidden } ?: restoredPages.last()
      val transaction = fragmentManager.beginTransaction()
      var hasChanges = false

      restoredPages.forEach { page ->
        if (page === visiblePage) {
          if (page.isHidden) {
            transaction.show(page)
            hasChanges = true
          }
        } else if (!page.isHidden) {
          transaction.hide(page)
          hasChanges = true
        }
      }

      if (hasChanges) {
        transaction.commitNow()
      }

      currentFragmentId = visiblePage.tag

      val visibleItem = navigationItems.firstOrNull { it.id == visiblePage.tag }
      updateTitleVisibility(visibleItem?.title)
      updateSubtitleVisibility(visibleItem?.subtitle)
      adapter.submitList(
          navigationItems.map { navItem -> navItem.copy(isSelected = navItem.id == visiblePage.tag) }
      )
      return
    }

    val firstItem = navigationItems.first()
    val firstFragment = fragmentCache.getOrPut(firstItem.id) {
      firstItem.action.fragmentClass?.java?.newInstance() 
          ?: throw IllegalStateException("First action must have a fragment")
    }

    fragmentManager.beginTransaction()
        .add(binding.fragmentContainer.id, firstFragment, firstItem.id)
        .commitNow()

    currentFragmentId = firstItem.id

    updateTitleVisibility(firstItem.title)
    updateSubtitleVisibility(firstItem.subtitle)
  }

  @JvmStatic
  internal fun NavDestination.matchDestination(route: String): Boolean =
      hierarchy.any { it.route == route }

  @JvmStatic
  internal fun NavDestination.matchDestination(@IdRes destId: Int): Boolean =
      hierarchy.any { it.id == destId }
}