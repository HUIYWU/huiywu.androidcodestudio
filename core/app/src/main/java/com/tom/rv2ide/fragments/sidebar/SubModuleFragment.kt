/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package com.tom.rv2ide.fragments.sidebar

import android.os.Bundle
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
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.editor.ProjectHandlerActivity
import com.tom.rv2ide.databinding.FragmentSubModuleBinding
import com.tom.rv2ide.projects.GradleProject
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.java.JavaModule
import com.tom.rv2ide.utils.ModuleCreator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Project module list, creation wizard, and module detail surface for the editor sidebar. */
class SubModuleFragment : Fragment() {

  private var _binding: FragmentSubModuleBinding? = null
  private val binding get() = checkNotNull(_binding)
  private val moduleCreator = ModuleCreator()
  private var screen = Screen.LIST
  private var selectedModule: GradleProject? = null
  private var wizardStep = 1
  private var moduleType = NewModuleType.ANDROID_LIBRARY
  private var moduleLanguage = ModuleLanguage.KOTLIN
  private var draftModuleName = "profile"
  private var draftGradlePath = ":profile"

  enum class ModuleLanguage { KOTLIN, JAVA }
  private enum class NewModuleType { ANDROID_LIBRARY }
  private enum class Screen { LIST, WIZARD, DETAIL }

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View {
    _binding = FragmentSubModuleBinding.inflate(inflater, container, false)
    return binding.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    binding.addModule.setOnClickListener { showWizard(1) }
    render()
  }

  private fun render() {
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
        render()
      }
    }
    val row = LinearLayout(requireContext()).apply {
      gravity = Gravity.CENTER_VERTICAL
      orientation = LinearLayout.HORIZONTAL
      setPadding(dp(16))
    }
    val icon = ImageView(requireContext()).apply {
      setImageResource(iconFor(module))
      contentDescription = null
      layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
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
    val header = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
    header.addView(button("<", false) { screen = Screen.LIST; render() }, LinearLayout.LayoutParams(dp(48), dp(48)))
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
      moduleType = NewModuleType.ANDROID_LIBRARY
      render()
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
    languages.addView(chip("Kotlin", moduleLanguage == ModuleLanguage.KOTLIN) { moduleLanguage = ModuleLanguage.KOTLIN })
    languages.addView(chip("Java", moduleLanguage == ModuleLanguage.JAVA) { moduleLanguage = ModuleLanguage.JAVA })
    content.addView(languages)
    content.addView(bottomActions("Back", "Next") {
      val path = pathInput.second.text.toString().trim()
      val name = nameInput.second.text.toString().trim().ifBlank { path.substringAfterLast(':') }
      if (!isValidPath(path) || path.count { it == ':' } != 1 || path.substringAfterLast(':') != name) {
        pathInput.first.error = "Use a single path matching the module name, such as :profile"
      } else {
        draftModuleName = name
        draftGradlePath = path
        showWizard(3)
      }
    })
  }

  private fun renderWizardPreview(content: LinearLayout) {
    content.addView(sectionTitle("Configuration"))
    val name = draftModuleName
    val path = draftGradlePath
    content.addView(text("Android library · ${moduleLanguage.name.lowercase().replaceFirstChar { it.uppercase() }}", 14f, secondary = true))
    content.addView(text("Module directory: ${path.removePrefix(":").replace(':', '/')}", 14f, secondary = true).apply { setPadding(0, dp(12), 0, 0) })
    content.addView(sectionTitle("Changes"))
    content.addView(text("+ settings.gradle(.kts) include($path)", 14f, secondary = true))
    content.addView(text("+ ${path.removePrefix(":")}/build.gradle(.kts)", 14f, secondary = true))
    content.addView(text("+ ${path.removePrefix(":")}/src/main/...", 14f, secondary = true))
    content.addView(bottomActions("Back", "Create and sync") { createModule(name, path) })
  }

  private fun renderModuleDetail() {
    val module = selectedModule ?: run { screen = Screen.LIST; render(); return }
    binding.moduleToolbar.visibility = View.GONE
    binding.moduleContent.removeAllViews()
    val scroll = scrollContent()
    val content = scrollBody(scroll)
    val header = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
    header.addView(button("<", false) { screen = Screen.LIST; render() }, LinearLayout.LayoutParams(dp(48), dp(48)))
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

  private fun createModule(moduleName: String, gradlePath: String) {
    val safeName = moduleName.ifBlank { gradlePath.substringAfterLast(':') }
    lifecycleScope.launch {
      val result = withContext(Dispatchers.IO) {
        moduleCreator.createModule(safeName, moduleLanguage, projectRoot())
      }
      if (!isAdded) return@launch
      if (result.success) {
        Toast.makeText(requireContext(), "Module created. Syncing project...", Toast.LENGTH_SHORT).show()
        screen = Screen.LIST
        render()
        syncProject()
      } else {
        Toast.makeText(requireContext(), result.errorMessage ?: "Unable to create module", Toast.LENGTH_LONG).show()
      }
    }
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
    (activity as? ProjectHandlerActivity)?.initializeProject()
  }

  private fun showWizard(step: Int) {
    wizardStep = step
    screen = Screen.WIZARD
    render()
  }

  private fun workspaceModules(): List<GradleProject> =
      IProjectManager.getInstance().getWorkspace()?.getSubProjects()?.filter { it.path != ":" }?.sortedBy { it.path } ?: emptyList()

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
        gravity = Gravity.END; setPadding(0, dp(24), 0, 0)
        back?.let { addView(button(it) { showWizard(wizardStep - 1) }) }
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

  private fun chip(label: String, checked: Boolean, onClick: () -> Unit) = Chip(requireContext()).apply {
    text = label; isCheckable = true; isChecked = checked; setOnClickListener { onClick() }
  }

  private fun button(label: String, secondary: Boolean = false, action: () -> Unit) = MaterialButton(requireContext()).apply {
    text = label; isAllCaps = false; setOnClickListener { action() }
  }

  private fun text(value: String, size: Float, secondary: Boolean = false) = TextView(requireContext()).apply {
    text = value
    textSize = size
    setTextColor(
        MaterialColors.getColor(
            this,
            if (secondary) {
              com.google.android.material.R.attr.colorOnSurfaceVariant
            } else {
              com.google.android.material.R.attr.colorOnSurface
            },
        ),
    )
  }

  private fun sectionTitle(value: String) = text(value, 18f).apply { setPadding(0, dp(16), 0, dp(8)) }

  private fun info(content: LinearLayout, label: String, value: String) {
    content.addView(text(label, 13f, secondary = true))
    content.addView(text(value, 15f).apply { setPadding(0, 0, 0, dp(12)) })
  }

  private fun projectRoot(): File = IProjectManager.getInstance().projectDir
  private fun defaultNamespace(): String = workspaceModules().filterIsInstance<AndroidModule>().firstOrNull()?.namespace ?: "com.example"
  private fun isValidPath(path: String) = path.matches(Regex("^(:[A-Za-z][A-Za-z0-9_-]*)+$"))
  private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
  private fun File.readTextSafe(): String = runCatching { readText() }.getOrDefault("")

  override fun onDestroyView() { super.onDestroyView(); _binding = null }
  override fun onDestroy() {
    super.onDestroy()
    com.tom.rv2ide.utils.EditorSidebarActions.removeFragmentFromCache("ide.editor.sidebar.subModule")
  }

}