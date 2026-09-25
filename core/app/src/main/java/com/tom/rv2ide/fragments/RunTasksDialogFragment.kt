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

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.transition.TransitionManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.blankj.utilcode.util.ThreadUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.transition.MaterialSharedAxis
import com.tom.rv2ide.R.string
import com.tom.rv2ide.adapters.RunTasksListAdapter
import com.tom.rv2ide.databinding.LayoutRunTaskBinding
import com.tom.rv2ide.databinding.LayoutRunTaskDialogBinding
import com.tom.rv2ide.flashbar.Flashbar
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.models.Checkable
import com.tom.rv2ide.projects.GradleProject
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.tasks.executeAsync
import com.tom.rv2ide.tooling.api.models.GradleTask
import com.tom.rv2ide.utils.SingleTextWatcher
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.flashbarBuilder
import com.tom.rv2ide.utils.infoIcon
import com.tom.rv2ide.utils.showOnUiThread
import com.tom.rv2ide.viewmodel.RunTasksViewModel
import org.slf4j.LoggerFactory

/**
 * A bottom sheet dialog fragment to show UI which allows the users to select and execute Gradle
 * tasks from the initialized project.
 *
 * @author Akash Yadav
 */
class RunTasksDialogFragment : BottomSheetDialogFragment() {

  private lateinit var binding: LayoutRunTaskDialogBinding
  private lateinit var run: LayoutRunTaskBinding
  private val viewModel: RunTasksViewModel by viewModels()
  private var activeFlashbar: Flashbar? = null
  private var waitingForFlashbarExit = false

  companion object {
    private val log = LoggerFactory.getLogger(RunTasksDialogFragment::class.java)

    private const val CHILD_LOADING = 0
    private const val CHILD_TASKS = 1
    private const val CHILD_CONFIRMATION = 2
    private const val CHILD_PROJECT_NOT_INITIALIZED = 3

    // The minimum amount of time (in milliseconds) the adapter should wait after the query is
    // changed before starting any further filter request.
    // A too less value here will result in UI lags
    private const val SEARCH_DELAY = 500L
  }

  private fun logDialogGeometry(dialog: Dialog, stage: String) {
    val decor = dialog.window?.decorView ?: return
    val decorLocation = IntArray(2)
    decor.getLocationOnScreen(decorLocation)
    log.warn(
        "run-tasks geometry {} decor=({}, {}) tY={} sY={} alpha={} windowAnimations={}",
        stage,
        decorLocation[0],
        decorLocation[1],
        decor.translationY,
        decor.scaleY,
        decor.alpha,
        dialog.window?.attributes?.windowAnimations,
    )

    val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
    if (bottomSheet != null) {
      val bottomSheetLocation = IntArray(2)
      bottomSheet.getLocationOnScreen(bottomSheetLocation)
      log.warn(
          "run-tasks geometry {} bottomSheet=({}, {}) tY={} sY={} alpha={}",
          stage,
          bottomSheetLocation[0],
          bottomSheetLocation[1],
          bottomSheet.translationY,
          bottomSheet.scaleY,
          bottomSheet.alpha,
      )
    }

    val decorGroup = decor as? ViewGroup ?: return
    for (index in 0 until decorGroup.childCount) {
      val child = decorGroup.getChildAt(index)
      val childLocation = IntArray(2)
      child.getLocationOnScreen(childLocation)
      log.warn(
          "run-tasks geometry {} child={} class={} pos=({}, {}) tY={} sY={} alpha={}",
          stage,
          index,
          child.javaClass.simpleName,
          childLocation[0],
          childLocation[1],
          child.translationY,
          child.scaleY,
          child.alpha,
      )
    }
  }

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    val dialog = object : BottomSheetDialog(requireContext(), theme) {
      override fun cancel() {
        val flashbar = activeFlashbar
        if (flashbar != null && (flashbar.isShown() || flashbar.isShowing())) {
          if (!waitingForFlashbarExit) {
            waitingForFlashbarExit = true
            logDialogGeometry(this, "cancel-deferred")
            log.warn("run-tasks dialog cancel deferred until flashbar exit")
            flashbar.dismiss()
          }
          return
        }
        super.cancel()
      }
    }
    log.warn("run-tasks dialog created")
    dialog.behavior.apply {
      peekHeight = (getWindowHeight() * 0.7).toInt()
      isFitToContents = false
      expandedOffset = 0
      addBottomSheetCallback(
          object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
              log.warn("run-tasks bottom sheet state changed: {}", newState)
              if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                log.warn("run-tasks bottom sheet hidden")
              }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
              if (slideOffset < 0f) {
                log.warn("run-tasks bottom sheet slide: {}", slideOffset)
              }
            }
          }
      )
    }
    dialog.setOnCancelListener {
      log.warn("run-tasks dialog cancel listener")
    }
    dialog.setOnDismissListener {
      log.warn("run-tasks dialog dismiss listener")
    }
    dialog.setOnShowListener {
      val bottomSheet =
          dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
      bottomSheet.layoutParams =
          bottomSheet.layoutParams.apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
          }
    }
    return dialog
  }

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View {
    this.binding = LayoutRunTaskDialogBinding.inflate(inflater, container, false)
    this.run = this.binding.run
    return binding.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    viewModel.observeDisplayedChild(viewLifecycleOwner) {
      val transition =
          MaterialSharedAxis(MaterialSharedAxis.X, it > this.binding.flipper.displayedChild)
      TransitionManager.beginDelayedTransition(this.binding.root, transition)
      this.binding.flipper.displayedChild = it
    }

    viewModel.observeQuery(viewLifecycleOwner) {
      val adapter = run.tasks.adapter as? RunTasksListAdapter? ?: return@observeQuery
      adapter.filter(it)
    }

    run.searchInput.editText?.addTextChangedListener(
        object : SingleTextWatcher() {
          val searchRunner = Runnable {
            viewModel.query = run.searchInput.editText?.text?.toString() ?: ""
          }

          override fun afterTextChanged(s: Editable?) {
            ThreadUtils.getMainHandler().removeCallbacks(searchRunner)
            ThreadUtils.runOnUiThreadDelayed(searchRunner, SEARCH_DELAY)
          }
        }
    )

    binding.exec.setOnClickListener {
      if (viewModel.selected.isEmpty()) {
        val dialogDecor = dialog?.window?.decorView as? ViewGroup
        if (dialogDecor != null) {
          activeFlashbar =
              requireActivity()
                  .flashbarBuilder()
                  .parentView(dialogDecor)
                  .infoIcon()
                  .message(getString(string.msg_err_select_tasks))
                  .barDismissListener(
                      object : Flashbar.OnBarDismissListener {
                        override fun onDismissing(bar: Flashbar, isSwiped: Boolean) {}

                        override fun onDismissProgress(bar: Flashbar, progress: Float) {}

                        override fun onDismissed(bar: Flashbar, event: Flashbar.DismissEvent) {
                          if (!waitingForFlashbarExit) {
                            return
                          }
                          waitingForFlashbarExit = false
                          activeFlashbar = null
                          log.warn("run-tasks flashbar exit finished, closing dialog")
                          dialog?.cancel()
                        }
                      }
                  )
                  .showOnUiThread()
        }
        return@setOnClickListener
      }

      if (viewModel.displayedChild == CHILD_TASKS) {
        binding.confirm.msg.text =
            getString(R.string.msg_tasks_to_run, viewModel.getSelectedTaskPaths())
        viewModel.displayedChild = CHILD_CONFIRMATION
        return@setOnClickListener
      }

      if (viewModel.displayedChild == CHILD_CONFIRMATION) {
        val buildService =
            Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
                ?: run {
                  log.error("Cannot find build service")
                  return@setOnClickListener
                }

        if (!buildService.isToolingServerStarted()) {
          flashError(R.string.msg_tooling_server_unavailable)
          return@setOnClickListener
        }

        val toRun = viewModel.selected.toTypedArray()
        buildService.executeTasks(*toRun)
        dismiss()
      }
    }

    binding.confirm.cancel.setOnClickListener { viewModel.displayedChild = CHILD_TASKS }

    viewModel.displayedChild = CHILD_LOADING

    executeAsync({
      val workspace =
          IProjectManager.getInstance().getWorkspace()
              ?: return@executeAsync emptyList<Checkable<GradleTask>>()

      return@executeAsync workspace
          .getSubProjects()
          .flatMap<GradleProject, GradleTask> { it.tasks }
          .map<GradleTask, Checkable<GradleTask>> { Checkable<GradleTask>(false, it) }
    }) { tasks ->
      viewModel.tasks = tasks ?: emptyList()
      viewModel.displayedChild =
          if (viewModel.tasks.isNotEmpty()) CHILD_TASKS else CHILD_PROJECT_NOT_INITIALIZED

      val onCheckChanged = { item: Checkable<GradleTask> ->
        if (item.isChecked) {
          viewModel.select(item.data.path)
        } else {
          viewModel.deselect(item.data.path)
        }
      }

      run.tasks.adapter = RunTasksListAdapter(viewModel.tasks, onCheckChanged)
    }
  }

  private fun getWindowHeight(): Int {
    val height =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          activity?.windowManager?.currentWindowMetrics?.bounds?.height()!!
        } else {
          val displayMetrics = DisplayMetrics()
          activity?.windowManager?.defaultDisplay?.getMetrics(displayMetrics)
          displayMetrics.heightPixels
        }
    return height
  }
}
