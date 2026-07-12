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

package com.tom.rv2ide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialSharedAxis
import com.tom.rv2ide.preferences.IPreference
import com.tom.rv2ide.preferences.IPreferenceGroup
import com.tom.rv2ide.preferences.IPreferenceScreen
import com.tom.rv2ide.R
import com.tom.rv2ide.preferences.QUICK_INPUT_CUSTOMIZE_REFRESH_RESULT
import com.tom.rv2ide.preferences.QUICK_INPUT_CUSTOMIZE_SCREEN_KEY
import com.tom.rv2ide.preferences.quickInputCustomizeChildren
import com.tom.rv2ide.preferences.selectedQuickInputTextType
import com.tom.rv2ide.utils.EditorQuickInputPreferences
import com.tom.rv2ide.preferences.observers.LSPStateObserver

class IDEPreferencesFragment : BasePreferenceFragment() {

  private var children: List<IPreference> = emptyList()
  private var screenKey: String? = null
  private var quickInputDragHelper: ItemTouchHelper? = null

  private val serverStateListener = {
    refreshPreferences()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true).apply {
        duration = 320
    }
    returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false).apply {
        duration = 320
    }
    exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true).apply {
        duration = 320
    }
    reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false).apply {
        duration = 320
    }
  }

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View {
    return super.onCreateView(inflater, container, savedInstanceState)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    if (arguments?.getString(EXTRA_SCREEN_KEY) == QUICK_INPUT_CUSTOMIZE_SCREEN_KEY) {
      installQuickInputDragging()
    }
  }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)

    if (context == null) {
      return
    }

    @Suppress("DEPRECATION")
    this.children = arguments?.getParcelableArrayList(EXTRA_CHILDREN) ?: emptyList()
    screenKey = arguments?.getString(EXTRA_SCREEN_KEY)
    if (screenKey == QUICK_INPUT_CUSTOMIZE_SCREEN_KEY) {
      this.children = quickInputCustomizeChildren()
      parentFragmentManager.setFragmentResultListener(
          QUICK_INPUT_CUSTOMIZE_REFRESH_RESULT,
          this,
      ) { _, _ ->
        this.children = quickInputCustomizeChildren()
        refreshPreferences()
      }
    }

    preferenceScreen.removeAll()
    addChildren(this.children, preferenceScreen)
  }

  override fun onResume() {
    super.onResume()
    LSPStateObserver.addListener(serverStateListener)
  }

  override fun onPause() {
    super.onPause()
    LSPStateObserver.removeListener(serverStateListener)
  }

  private fun installQuickInputDragging() {
    if (quickInputDragHelper != null) return

    val recyclerView = listView
    val adapter = recyclerView.adapter as? PreferenceGroupAdapter ?: return
    var orderedIds = emptyList<String>()
    var dragged = false

    fun itemId(holder: RecyclerView.ViewHolder): String? =
        holder.itemView.getTag(R.id.quick_input_drag_handle) as? String

    val callback = object : ItemTouchHelper.Callback() {
      override fun isLongPressDragEnabled() = false

      override fun isItemViewSwipeEnabled() = false

      override fun getMovementFlags(
          recyclerView: RecyclerView,
          viewHolder: RecyclerView.ViewHolder,
      ): Int {
        return if (itemId(viewHolder) != null) {
          makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
        } else {
          0
        }
      }

      override fun onMove(
          recyclerView: RecyclerView,
          source: RecyclerView.ViewHolder,
          target: RecyclerView.ViewHolder,
      ): Boolean {
        val sourceId = itemId(source) ?: return false
        val targetId = itemId(target) ?: return false
        val sourceIndex = orderedIds.indexOf(sourceId)
        val targetIndex = orderedIds.indexOf(targetId)
        if (sourceIndex < 0 || targetIndex < 0 || sourceIndex == targetIndex) return false

        orderedIds = orderedIds.toMutableList().apply {
          removeAt(sourceIndex)
          add(targetIndex, sourceId)
        }
        recyclerView.adapter?.notifyItemMoved(source.bindingAdapterPosition, target.bindingAdapterPosition)
        dragged = true
        return true
      }

      override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

      override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && itemId(viewHolder ?: return) != null) {
          orderedIds = EditorQuickInputPreferences.customizationItems(selectedQuickInputTextType())
              .map(EditorQuickInputPreferences.StoredItem::id)
          dragged = false
        }
      }

      override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        if (!dragged) return

        val type = selectedQuickInputTextType()
        val itemsById = EditorQuickInputPreferences.customizationItems(type).associateBy(
            EditorQuickInputPreferences.StoredItem::id,
        )
        EditorQuickInputPreferences.saveItems(type, orderedIds.mapNotNull(itemsById::get))
        dragged = false
        this@IDEPreferencesFragment.children = quickInputCustomizeChildren()
        refreshPreferences()
      }
    }

    quickInputDragHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(recyclerView) }
    fun bindDragHandle(view: View) {
      val holder = recyclerView.getChildViewHolder(view)
      val position = holder.bindingAdapterPosition
      if (position == RecyclerView.NO_POSITION) return
      val key = adapter.getItem(position)?.key ?: return
      if (!key.startsWith("idepref_editor_quickInputItem:")) return

      view.setTag(R.id.quick_input_drag_handle, key.substringAfter(':'))
      view.findViewById<View>(R.id.quick_input_drag_handle)?.setOnTouchListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
          quickInputDragHelper?.startDrag(holder)
        }
        true
      }
    }
    recyclerView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
      override fun onChildViewAttachedToWindow(view: View) = bindDragHandle(view)

      override fun onChildViewDetachedFromWindow(view: View) = Unit
    })
    for (index in 0 until recyclerView.childCount) {
      bindDragHandle(recyclerView.getChildAt(index))
    }
  }

  private fun refreshPreferences() {
    if (context == null) {
      return
    }

    preferenceScreen.removeAll()
    addChildren(this.children, preferenceScreen)
  }

  private fun addChildren(children: List<IPreference>, pref: PreferenceGroup) {
    for (child in children) {
      val preference = child.onCreateView(requireContext())
      if (child is IPreferenceScreen) {
        preference.fragment = IDEPreferencesFragment::class.java.name
        preference.extras.putString(EXTRA_SCREEN_KEY, child.key)
        preference.extras.putParcelableArrayList(EXTRA_CHILDREN, ArrayList(child.children))
        pref.addPreference(preference)
        continue
      }

      if (child is IPreferenceGroup) {
        pref.addPreference(preference as PreferenceCategory)
        addChildren(child.children, preference)
        continue
      }

      pref.addPreference(preference)
    }
  }

  companion object {
    const val EXTRA_CHILDREN = "ide.preferences.fragment.children"
    const val EXTRA_SCREEN_KEY = "ide.preferences.fragment.screenKey"
  }
}