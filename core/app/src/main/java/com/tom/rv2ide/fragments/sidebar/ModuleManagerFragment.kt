/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package com.tom.rv2ide.fragments.sidebar

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.transition.TransitionManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.transition.MaterialSharedAxis
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.editor.ProjectHandlerActivity
import com.tom.rv2ide.databinding.FragmentModuleManagerBinding
import com.tom.rv2ide.projects.GradleProject
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.java.JavaModule
import com.tom.rv2ide.utils.ModuleCreator
import com.tom.rv2ide.viewmodel.EditorViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** Project module list, creation wizard, and module detail surface for the editor sidebar. */
class ModuleManagerFragment : Fragment() {

  private val log = LoggerFactory.getLogger(ModuleManagerFragment::class.java)
  private var _binding: FragmentModuleManagerBinding? = null
  private val binding get() = checkNotNull(_binding)
  private val moduleCreator = ModuleCreator()
  private val editorViewModel by viewModels<EditorViewModel>(ownerProducer = { requireActivity() })
  private var refreshAfterSync = false
  private var creatingModule = false
  private var screen = Screen.LIST
  private var selectedModule: GradleProject? = null
  private var wizardStep = 1
  private var moduleType = NewModuleType.ANDROID_LIBRARY
  private var moduleLanguage = ModuleLanguage.KOTLIN
  private var draftModuleName = "profile"
  private var draftGradlePath = ":profile"
  private var useKotlinDsl = true
  private var creationStatusDialog: AlertDialog? = null
  private var creationStatusMessage: TextView? = null
  private var creationStatusProgress: CircularProgressIndicator? = null

  enum class ModuleLanguage { KOTLIN, JAVA }
  private enum class NewModuleType { ANDROID_LIBRARY }
  private enum class Screen { LIST, WIZARD, DETAIL }

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View {
    _binding = FragmentModuleManagerBinding.inflate(inflater, container, false)
    return binding.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    useKotlinDsl = usesKotlinSettings(projectRoot())
    binding.addModule.setOnClickListener { showWizard(1) }
    binding.moduleEmptyState.message = "Project modules will appear after initialization finishes."
    editorViewModel._isInitializing.observe(viewLifecycleOwner) { initializing ->
      binding.addModule.isEnabled = !initializing
      if (refreshAfterSync && !initializing) {
        refreshAfterSync = false
        selectedModule = null
        screen = Screen.LIST
      }
      if (!initializing || IProjectManager.getInstance().getWorkspace() == null) {
        render()
      }
    }
    render()
  }

  private fun render(animated: Boolean = false, forward: Boolean = true) {
    if (IProjectManager.getInstance().getWorkspace() == null) {
      binding.moduleFlipper.displayedChild = 0
      return
    }
    binding.moduleFlipper.displayedChild = 1
    if (animated) {
      TransitionManager.beginDelayedTransition(
          binding.moduleContent,
          MaterialSharedAxis(MaterialSharedAxis.X, forward),
      )
    }
    when (screen) {
      Screen.LIST -> renderModuleList()
      Screen.WIZARD -> renderWizard()
      Screen.DETAIL -> renderModuleDetail()
    }
  }

  private fun renderModuleList() {
    binding.moduleToolbar.visibility = View.VISIBLE
    binding.moduleContent.removeAllViews()
    val scroll = scrollContent()
    val content = scrollBody(scroll)
    val modules = workspaceModules()
    binding.moduleCount.text = if (modules.size == 1) "1 module" else "${modules.size} modules"
    if (modules.isEmpty()) {
      content.addView(text("Project modules will appear after a successful sync.", 16f, secondary = true))
    } else {
      modules.forEach { content.addView(moduleCard(it)) }
    }
    binding.moduleContent.addView(scroll)
  }

  private fun moduleCard(module: GradleProject): View {
    val card = MaterialCardView(requireContext()).apply {
      radius = dp(12).toFloat()
      cardElevation = dp(2).toFloat()
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(12)
      }
      isClickable = true
      isFocusable = true
      setOnClickListener {
        selectedModule = module
        screen = Screen.DETAIL
        render(animated = true, forward = true)
      }
    }
    val row = LinearLayout(requireContext()).apply {
      gravity = Gravity.CENTER_VERTICAL
      orientation = LinearLayout.HORIZONTAL
      setPadding(dp(12))
    }
    val icon = ImageView(requireContext()).apply {
      setImageResource(iconFor(module))
      imageTintList = ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
      contentDescription = null
      layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
    }
    val labels = LinearLayout(requireContext()).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = dp(12)
      }
    }
    labels.addView(text(module.path, 16f))
    labels.addView(text(moduleSubtitle(module), 13f, secondary = true))
    val chevron = text(">", 24f, secondary = true).apply { gravity = Gravity.CENTER }
    row.addView(icon)
    row.addView(labels)
    row.addView(chevron, LinearLayout.LayoutParams(dp(28), dp(40)))
    card.addView(row)
    return card
  }
  private fun renderWizard() {
    binding.moduleToolbar.visibility = View.GONE
    binding.moduleContent.removeAllViews()
    val scroll = scrollContent()
    val content = scrollBody(scroll)
    content.addView(backToolbar { showModuleList() })
    val header = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
    header.addView(text("New module", 20f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    header.addView(text("$wizardStep / 3", 14f, secondary = true))
    content.addView(header)
    when (wizardStep) {
      1 -> renderWizardType(content)
      2 -> renderWizardName(content)
      else -> renderWizardPreview(content)
    }
    binding.moduleContent.addView(scroll)
  }

  private fun renderWizardType(content: LinearLayout) {
    content.addView(sectionTitle("Module type"))
    content.addView(choiceCard("Android library", "Android resources, manifest, and Android Gradle plugin", moduleType == NewModuleType.ANDROID_LIBRARY) {
      if (moduleType != NewModuleType.ANDROID_LIBRARY) {
        moduleType = NewModuleType.ANDROID_LIBRARY
        render()
      }
    })
    content.addView(choiceCard("Java/Kotlin library", "Planned. This module type is not created until its Gradle template is supported.", false) {
      Toast.makeText(requireContext(), "Java/Kotlin library creation is not available yet", Toast.LENGTH_SHORT).show()
    })
    content.addView(bottomActions(null, "Next") { showWizard(2) })
  }

  private fun renderWizardName(content: LinearLayout) {
    content.addView(sectionTitle("Name and location"))
    val nameInput = input("Module name", draftModuleName)
    val pathInput = input("Gradle path", draftGradlePath)
    content.addView(nameInput.first)
    content.addView(pathInput.first)
    content.addView(text("Language", 14f, secondary = true).apply { setPadding(0, dp(12), 0, dp(4)) })
    val languages = ChipGroup(requireContext()).apply { isSingleSelection = true; isSelectionRequired = true }
    languages.addView(chip("Kotlin", moduleLanguage == ModuleLanguage.KOTLIN) {
      if (moduleLanguage != ModuleLanguage.KOTLIN) {
        moduleLanguage = ModuleLanguage.KOTLIN
        render()
      }
    })
    languages.addView(chip("Java", moduleLanguage == ModuleLanguage.JAVA) {
      if (moduleLanguage != ModuleLanguage.JAVA) {
        moduleLanguage = ModuleLanguage.JAVA
        render()
      }
    })
    content.addView(languages)
    content.addView(text("Gradle DSL", 14f, secondary = true).apply { setPadding(0, dp(12), 0, dp(4)) })
    val dsl = ChipGroup(requireContext()).apply { isSingleSelection = true; isSelectionRequired = true }
    dsl.addView(chip("Kotlin", useKotlinDsl) { if (!useKotlinDsl) { useKotlinDsl = true; render() } })
    dsl.addView(chip("Groovy", !useKotlinDsl) { if (useKotlinDsl) { useKotlinDsl = false; render() } })
    content.addView(dsl)
    content.addView(bottomActions("Back", "Next") {
      val path = pathInput.second.text.toString().trim()
      val name = nameInput.second.text.toString().trim().ifBlank { path.substringAfterLast(':') }
      if (!isValidPath(path) || path.substringAfterLast(':') != name) {
         pathInput.first.error = "Use a Gradle path whose final segment matches the module name, such as :feature:profile"
      } else {
        draftModuleName = name
        draftGradlePath = path
        showWizard(3)
      }
    })
  }

  private fun renderWizardPreview(content: LinearLayout) {
    content.addView(sectionTitle("Configuration"))
    val path = draftGradlePath
    val moduleDirectory = path.removePrefix(":").replace(':', '/')
    val settingsScript = if (usesKotlinSettings(projectRoot())) "settings.gradle.kts" else "settings.gradle"
    val moduleBuildScript = if (useKotlinDsl) "build.gradle.kts" else "build.gradle"
    val appBuildScript = if (File(projectRoot(), "app/build.gradle.kts").isFile) "app/build.gradle.kts" else "app/build.gradle"
    content.addView(text("Android library · ${moduleLanguage.name.lowercase().replaceFirstChar { it.uppercase() }} · ${if (useKotlinDsl) "Kotlin DSL" else "Groovy DSL"}", 14f, secondary = true))
    content.addView(text("Module directory: $moduleDirectory", 14f, secondary = true).apply { setPadding(0, dp(12), 0, 0) })
    content.addView(sectionTitle("Changes"))
    content.addView(text("+ $settingsScript include($path)", 14f, secondary = true))
    content.addView(text("+ $moduleDirectory/$moduleBuildScript", 14f, secondary = true))
    if (File(projectRoot(), appBuildScript).isFile) {
      content.addView(text("+ $appBuildScript implementation(project($path))", 14f, secondary = true))
    }
    content.addView(text("+ $moduleDirectory/proguard-rules.pro", 14f, secondary = true))
    content.addView(text("+ $moduleDirectory/consumer-rules.pro", 14f, secondary = true))
    val sourceDirectory = if (moduleLanguage == ModuleLanguage.KOTLIN) "kotlin" else "java"
    val sampleFile = if (moduleLanguage == ModuleLanguage.KOTLIN) "SampleClass.kt" else "SampleClass.java"
    content.addView(text("+ $moduleDirectory/src/main/$sourceDirectory/.../$sampleFile", 14f, secondary = true))
    content.addView(bottomActions("Back", "Create and sync") { createModule(path) })
  }

  private fun renderModuleDetail() {
    val module = selectedModule ?: run { showModuleList(animated = false); return }
    binding.moduleToolbar.visibility = View.GONE
    binding.moduleContent.removeAllViews()
    val scroll = scrollContent()
    val content = scrollBody(scroll)
    content.addView(backToolbar { showModuleList() })
    val header = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
    val titleGroup = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
    titleGroup.addView(text(module.path, 20f))
    titleGroup.addView(text(moduleSubtitle(module), 13f, secondary = true))
    header.addView(titleGroup, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    content.addView(header)
    val tabs = TabLayout(requireContext())
    listOf("Overview", "Build", "Dependencies").forEach { tabs.addTab(tabs.newTab().setText(it)) }
    content.addView(tabs)
    val detail = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, 0) }
    fun showTab(index: Int) {
      detail.removeAllViews()
      when (index) {
        0 -> populateOverview(detail, module)
        1 -> populateBuild(detail, module)
        else -> populateDependencies(detail, module)
      }
    }
    tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
      override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
      override fun onTabUnselected(tab: TabLayout.Tab) = Unit
      override fun onTabReselected(tab: TabLayout.Tab) = Unit
    })
    showTab(0)
    content.addView(detail)
    binding.moduleContent.addView(scroll)
  }

  private fun populateOverview(content: LinearLayout, module: GradleProject) {
    content.addView(sectionTitle("Module information"))
    info(content, "Type", moduleSubtitle(module).substringBefore(" ·"))
    info(content, "Directory", module.projectDir.relativeToOrSelf(projectRoot()).path)
    info(content, "Build script", module.buildScript.name)
    if (module is AndroidModule) {
      info(content, "Namespace", module.namespace ?: "Unavailable")
      info(content, "Build variant", module.configuredVariant?.name ?: "Default")
    }
    content.addView(button("Open build script") { openBuildScript(module) })
    content.addView(button("Sync project") { syncProject() })
  }

  private fun populateBuild(content: LinearLayout, module: GradleProject) {
    content.addView(sectionTitle("Build configuration"))
    if (module is AndroidModule) {
      info(content, "Namespace", module.namespace ?: "Unavailable")
      info(content, "Selected variant", module.configuredVariant?.name ?: "Default")
      info(content, "Available variants", module.variants.joinToString { it.name }.ifEmpty { "Unavailable" })
      content.addView(text("Edit Android build settings in the module build script. Save changes and sync to refresh this model.", 14f, secondary = true))
    } else {
      info(content, "Module type", moduleSubtitle(module))
      content.addView(text("Language target and Gradle configuration are read from the synchronized module model.", 14f, secondary = true))
    }
    content.addView(button("Open build script") { openBuildScript(module) })
  }

  private fun populateDependencies(content: LinearLayout, module: GradleProject) {
    content.addView(sectionTitle("Dependencies"))
    val dependencies = when (module) {
      is AndroidModule -> module.getCompileModuleProjects().map { it.path }
      is JavaModule -> module.getCompileModuleProjects().map { it.path }
      is ModuleProject -> module.getCompileModuleProjects().map { it.path }
      else -> emptyList()
    }
    if (dependencies.isEmpty()) content.addView(text("No project dependencies reported by the current workspace.", 14f, secondary = true))
    else dependencies.sorted().forEach { content.addView(text(it, 15f)) }
  }

  private fun createModule(gradlePath: String) {
    if (creatingModule || editorViewModel.isInitializing) return

    creatingModule = true
    render()
    showCheckingDialog()
    lifecycleScope.launch {
      val preflight = withContext(Dispatchers.IO) {
        moduleCreator.preflightModuleCreation(gradlePath, moduleLanguage, projectRoot(), useKotlinDsl)
      }
      if (!isAdded) return@launch
      if (!preflight.success) {
        creatingModule = false
        render()
        showCreationError(preflight.errorMessage ?: "The module configuration could not be checked.")
        return@launch
      }

      dismissCreationStatusDialog()
      screen = Screen.LIST
      render()
      val result = withContext(Dispatchers.IO) {
        moduleCreator.createPreflightValidatedModule(gradlePath, moduleLanguage, projectRoot(), useKotlinDsl)
      }
      if (!isAdded) return@launch
      creatingModule = false
      if (result.success) {
        Toast.makeText(requireContext(), "Module created. Syncing project...", Toast.LENGTH_SHORT).show()
        syncProject()
      } else {
        render()
        showCreationError(result.errorMessage ?: "Unable to create module files.")
      }
    }
  }

  private fun showCheckingDialog() {
    dismissCreationStatusDialog()
    val content = LinearLayout(requireContext()).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(24))
    }
    val progress = CircularProgressIndicator(requireContext()).apply {
      isIndeterminate = true
      layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
    }
    val message = text("Checking Gradle configuration...", 16f).apply {
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = dp(16)
      }
    }
    content.addView(progress)
    content.addView(message)
    creationStatusMessage = message
    creationStatusProgress = progress
    creationStatusDialog =
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Preparing module")
            .setView(content)
            .setPositiveButton("Close", null)
            .setCancelable(false)
            .create()
            .also { dialog ->
              dialog.setCanceledOnTouchOutside(false)
              dialog.show()
              dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility = View.GONE
            }
  }

  private fun showCreationError(message: String) {
    val dialog = creationStatusDialog
    if (dialog == null || !dialog.isShowing) {
      MaterialAlertDialogBuilder(requireContext())
          .setTitle("Module creation failed")
          .setMessage(message)
          .setPositiveButton("Close", null)
          .show()
      return
    }
    creationStatusProgress?.visibility = View.GONE
    creationStatusMessage?.text = message
    dialog.setTitle("Module creation failed")
    dialog.setCancelable(true)
    dialog.setCanceledOnTouchOutside(true)
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
      visibility = View.VISIBLE
      setOnClickListener { dismissCreationStatusDialog() }
    }
  }

  private fun dismissCreationStatusDialog() {
    creationStatusDialog?.dismiss()
    creationStatusDialog = null
    creationStatusMessage = null
    creationStatusProgress = null
  }

  private fun openBuildScript(module: GradleProject) {
    val script = module.buildScript
    if (!script.isFile) {
      Toast.makeText(requireContext(), "Build script is unavailable", Toast.LENGTH_SHORT).show()
      return
    }
    val editor = activity as? com.tom.rv2ide.activities.editor.EditorHandlerActivity
    if (editor == null) {
      Toast.makeText(requireContext(), "Editor is unavailable", Toast.LENGTH_SHORT).show()
      return
    }
    editor.openFile(script)
  }

  private fun syncProject() {
    val activity = activity as? ProjectHandlerActivity
    if (activity == null) {
      log.warn("Module synchronization skipped; editor activity is unavailable")
      return
    }
    if (editorViewModel.isInitializing) {
      log.warn("Module synchronization skipped; project initialization is already running")
      return
    }
    refreshAfterSync = true
    render()
    log.warn("Invoking ProjectHandlerActivity.initializeProject after module creation")
    activity.initializeProject()
  }

  private fun showWizard(step: Int) {
    val forward = screen != Screen.WIZARD || step >= wizardStep
    wizardStep = step
    screen = Screen.WIZARD
    render(animated = true, forward = forward)
  }

  private fun showModuleList(animated: Boolean = true) {
    selectedModule = null
    screen = Screen.LIST
    render(animated = animated, forward = false)
  }

  private fun workspaceModules(): List<GradleProject> =
      IProjectManager.getInstance().getWorkspace()?.getSubProjects()
          ?.filter { it.path != ":" && isConcreteModule(it) }
          ?.sortedBy { it.path }
          ?: emptyList()

  private fun isConcreteModule(module: GradleProject): Boolean {
    // Gradle creates implicit parent projects for nested paths such as :feature:profile.
    // They have no build script of their own and should not appear as user modules.
    return File(module.projectDir, "build.gradle.kts").isFile ||
        File(module.projectDir, "build.gradle").isFile
  }

  private fun moduleSubtitle(module: GradleProject): String = when (module) {
    is AndroidModule -> when {
      module.isApplication -> "Android Application · ${module.configuredVariant?.name ?: "default"}"
      module.isLibrary -> "Android Library · ${module.configuredVariant?.name ?: "default"}"
      else -> "Android Module · ${module.configuredVariant?.name ?: "default"}"
    }
    is JavaModule -> if (module.buildScript.readTextSafe().contains("kotlin")) "Kotlin Library" else "Java Library"
    else -> "Gradle Module"
  }

  private fun iconFor(module: GradleProject): Int = when (module) {
    is AndroidModule -> R.drawable.ic_android
    is JavaModule -> if (module.buildScript.readTextSafe().contains("kotlin")) R.drawable.ic_language_kotlin else R.drawable.ic_language_java
    else -> R.drawable.ic_language_java
  }

  private fun scrollContent(): NestedScrollView {
    val content = LinearLayout(requireContext()).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(0, 0, 0, dp(16))
    }
    return NestedScrollView(requireContext()).apply {
      isFillViewport = true
      addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
  }

  private fun scrollBody(container: NestedScrollView): LinearLayout = container.getChildAt(0) as LinearLayout

  private fun choiceCard(title: String, description: String, selected: Boolean, onClick: () -> Unit): View =
      MaterialCardView(requireContext()).apply {
        radius = dp(12).toFloat(); cardElevation = dp(2).toFloat(); isCheckable = true; isChecked = selected
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        setOnClickListener { onClick() }
        addView(LinearLayout(requireContext()).apply {
          orientation = LinearLayout.VERTICAL; setPadding(dp(16)); addView(text(title, 16f)); addView(text(description, 13f, secondary = true))
        })
      }

  private fun bottomActions(back: String?, next: String, action: () -> Unit): View =
      LinearLayout(requireContext()).apply {
        gravity = Gravity.END
        setPadding(0, dp(24), 0, 0)
        back?.let {
          addView(
              outlinedButton(it) { showWizard(wizardStep - 1) },
              LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(12)
              },
          )
        }
        addView(button(next) { action() })
      }

  private fun input(hint: String, value: String): Pair<TextInputLayout, EditText> {
    val layout = TextInputLayout(requireContext()).apply {
      this.hint = hint
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }
    val edit = EditText(requireContext()).apply { setText(value); inputType = InputType.TYPE_CLASS_TEXT }
    layout.addView(edit)
    return layout to edit
  }

  private fun chip(label: String, checked: Boolean, onClick: () -> Unit) =
      Chip(
              ContextThemeWrapper(
                  requireContext(),
                  com.google.android.material.R.style.Widget_Material3_Chip_Filter,
              ),
          )
          .apply {
            text = label
            isCheckable = true
            setCheckedIconVisible(true)
            isChecked = checked
            setOnClickListener { onClick() }
          }

  private fun button(label: String, secondary: Boolean = false, action: () -> Unit) = MaterialButton(requireContext()).apply {
    text = label
    isAllCaps = false
    isEnabled = !creatingModule && !editorViewModel.isInitializing
    setOnClickListener { action() }
  }

  private fun outlinedButton(label: String, action: () -> Unit) =
      MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label
        isAllCaps = false
        isEnabled = !creatingModule && !editorViewModel.isInitializing
        setOnClickListener { action() }
      }

  private fun backToolbar(action: () -> Unit) = MaterialToolbar(requireContext()).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
      bottomMargin = dp(4)
    }
    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
    navigationIcon = requireContext().getDrawable(R.drawable.ic_arrow_back)
    setNavigationIconTint(themeColor(com.google.android.material.R.attr.colorOnSurface))
    contentDescription = "Back to modules"
    isEnabled = !creatingModule && !editorViewModel.isInitializing
    setNavigationOnClickListener {
      if (!creatingModule && !editorViewModel.isInitializing) action()
    }
  }

  private fun text(value: String, size: Float, secondary: Boolean = false) = TextView(requireContext()).apply {
    text = value
    textSize = size
    setTextColor(
        themeColor(
            if (secondary) {
              com.google.android.material.R.attr.colorOnSurfaceVariant
            } else {
              com.google.android.material.R.attr.colorOnSurface
            },
        ),
    )
  }

  private fun themeColor(attribute: Int): Int = MaterialColors.getColor(requireContext(), attribute, 0)

  private fun sectionTitle(value: String) = text(value, 18f).apply { setPadding(0, dp(16), 0, dp(8)) }

  private fun info(content: LinearLayout, label: String, value: String) {
    content.addView(text(label, 13f, secondary = true))
    content.addView(text(value, 15f).apply { setPadding(0, 0, 0, dp(12)) })
  }

  private fun projectRoot(): File = IProjectManager.getInstance().projectDir

  private fun usesKotlinSettings(projectRoot: File): Boolean =
      File(projectRoot, "settings.gradle.kts").isFile || !File(projectRoot, "settings.gradle").isFile
private fun defaultNamespace(): String = workspaceModules().filterIsInstance<AndroidModule>().firstOrNull()?.namespace ?: "com.example"
  private fun isValidPath(path: String) = path.matches(Regex("^(:[A-Za-z][A-Za-z0-9_-]*)+$"))
  private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

  // Build scripts can be absent or temporarily unreadable while Gradle refreshes the workspace.
  private fun File.readTextSafe(): String = runCatching { readText() }.getOrDefault("")

  override fun onDestroyView() {
    dismissCreationStatusDialog()
    super.onDestroyView()
    _binding = null
  }
  override fun onDestroy() {
    super.onDestroy()
    com.tom.rv2ide.utils.EditorSidebarActions.removeFragmentFromCache("ide.editor.sidebar.moduleManager")
  }

}