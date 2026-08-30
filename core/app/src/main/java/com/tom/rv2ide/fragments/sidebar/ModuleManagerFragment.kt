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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import androidx.core.widget.addTextChangedListener
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.editor.ProjectHandlerActivity
import com.tom.rv2ide.databinding.FragmentModuleManagerBinding
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.GradleProject
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.gradleedit.ModuleOperations
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.projects.java.JavaModule
import com.tom.rv2ide.tooling.api.models.ApplicationProjectInfo
import com.tom.rv2ide.tooling.api.models.GradleDsl
import com.tom.rv2ide.tooling.api.models.ModuleCreationKind
import com.tom.rv2ide.tooling.api.models.ModuleSourceLanguage
import com.tom.rv2ide.utils.ModuleCreationRequest
import com.tom.rv2ide.utils.ModuleCreator
import com.tom.rv2ide.viewmodel.EditorViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** Project module list, creation wizard, and module detail surface for the editor sidebar. */
class ModuleManagerFragment : Fragment() {

  companion object {
    private const val MENU_RENAME_MODULE = 1
    private const val MENU_MOVE_MODULE = 2
    private const val MENU_DELETE_MODULE = 3
  }

  private val log = LoggerFactory.getLogger(ModuleManagerFragment::class.java)
  private var _binding: FragmentModuleManagerBinding? = null
  private val binding get() = checkNotNull(_binding)
  private val moduleCreator = ModuleCreator()
  private val editorViewModel by viewModels<EditorViewModel>(ownerProducer = { requireActivity() })
  private var refreshAfterSync = false
  private var creatingModule = false
  private var screen = Screen.LIST
  private var selectedModule: GradleProject? = null
  private val wizardStepContentId = View.generateViewId()
  private var wizardScroll: NestedScrollView? = null
  private var wizardStepContent: LinearLayout? = null
  private var wizardStepIndicator: TextView? = null
  private var wizardStep = 1
  private var moduleType = ModuleCreationKind.ANDROID_LIBRARY
  private var moduleLanguage = ModuleSourceLanguage.KOTLIN
  private var draftSourcePackageName = "com.example.profile"
  private var draftGradlePath = ":profile"
  private var overwriteGradlePathDirectory = false
  private var draftOverrideDirectory = "profile"
  private var useKotlinDsl = true
  private var applicationProjects: List<String>? = null
  private var applicationProjectDetails: List<ApplicationProjectInfo> = emptyList()
  private var selectedApplicationPath: String? = null
  private var applicationProjectsJob: Job? = null
  private var creationStatusDialog: AlertDialog? = null
  private var creationStatusMessage: TextView? = null
  private var creationStatusProgress: CircularProgressIndicator? = null

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
    binding.addModule.setOnClickListener { loadApplicationProjectsAndShowWizard() }
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

  private fun render(
      animated: Boolean = false,
      forward: Boolean = true,
      wizardStepChange: Boolean = false,
  ) {
    if (IProjectManager.getInstance().getWorkspace() == null) {
      binding.moduleFlipper.displayedChild = 0
      return
    }
    binding.moduleFlipper.displayedChild = 1
    if (animated) {
      val transition = MaterialSharedAxis(MaterialSharedAxis.X, forward)
      if (wizardStepChange && wizardStepContent?.parent != null) {
        TransitionManager.beginDelayedTransition(checkNotNull(wizardStepContent), transition)
      } else {
        // Entering or leaving the wizard is a screen transition. Animate the
        // whole module content, regardless of stale wizard view references.
        TransitionManager.beginDelayedTransition(binding.moduleContent, transition)
      }
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
    val existingStepContent = wizardStepContent
    val existingScroll = wizardScroll
    if (existingStepContent != null && existingScroll != null && existingScroll.parent === binding.moduleContent) {
      wizardStepIndicator?.text = "$wizardStep/3"
      existingStepContent.removeAllViews()
      renderWizardStep(existingStepContent)
      return
    }

    binding.moduleContent.removeAllViews()
    val scroll = scrollContent()
    val content = scrollBody(scroll)
    val navigationRow = LinearLayout(requireContext()).apply {
      gravity = Gravity.CENTER_VERTICAL
    }
    navigationRow.addView(
        backToolbar(action = { showModuleList() }).apply { title = "New module" },
        LinearLayout.LayoutParams(0, dp(48), 1f),
    )
    val stepIndicator = text("$wizardStep/3", 14f, secondary = true)
    navigationRow.addView(stepIndicator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    content.addView(navigationRow)
    val stepContent = LinearLayout(requireContext()).apply {
      id = wizardStepContentId
      orientation = LinearLayout.VERTICAL
      setPadding(0, dp(16), 0, 0)
    }
    content.addView(stepContent)
    binding.moduleContent.addView(scroll)
    wizardScroll = scroll
    wizardStepContent = stepContent
    wizardStepIndicator = stepIndicator
    renderWizardStep(stepContent)
  }

  private fun renderWizardStep(content: LinearLayout) {
    when (wizardStep) {
      1 -> renderWizardType(content)
      2 -> renderWizardName(content)
      else -> renderWizardPreview(content)
    }
  }
  private fun renderWizardType(content: LinearLayout) {

    content.addView(choiceCard(
        title = "Android library",
        description = "Android res, manifest, and Gradle plugin",
        icon = R.drawable.ic_android,
        selected = moduleType == ModuleCreationKind.ANDROID_LIBRARY,
    ) {
      moduleType = ModuleCreationKind.ANDROID_LIBRARY
      render()
    })
    content.addView(choiceCard(
        title = "Java/Kotlin library",
        description = "JVM library with Java or Kotlin source",
        icon = R.drawable.duke_bw,
        selected = moduleType == ModuleCreationKind.JAVA_LIBRARY,
    ) {
      moduleType = ModuleCreationKind.JAVA_LIBRARY
      render()
    })
    content.addView(bottomActions(null, "Next") { showWizard(2) })
  }

  private fun renderWizardName(content: LinearLayout) {
    val packageInputLabel = if (moduleType == ModuleCreationKind.ANDROID_LIBRARY) "Namespace" else "Package name"
    val packageInput = input(packageInputLabel, draftSourcePackageName, R.drawable.ic_package, dense = true).apply {
      first.helperText = "Directly affects the source code directory"
      first.layoutParams = (first.layoutParams as LinearLayout.LayoutParams).apply {
        topMargin = 0
      }
    }
    val pathInput = input("Gradle path", draftGradlePath, R.drawable.ic_gradle, dense = true).apply {
      first.helperText = "By default, a directory is created at this path"
    }
    val defaultDirectory = draftGradlePath.trim().trim(':').replace(':', '/')
    if (!overwriteGradlePathDirectory) draftOverrideDirectory = defaultDirectory
    val overrideInput = input(
        "Directory",
        draftOverrideDirectory,
        R.drawable.ic_folder,
        dense = true,
        enabled = overwriteGradlePathDirectory,
    ).apply {
      first.helperText = "Directory used when overwriting generated files"
    }
    val overwriteSwitch = MaterialSwitch(requireContext()).apply {
      text = "Overwrite the Gradle path directory"
      isChecked = overwriteGradlePathDirectory
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(8)
      }
      setOnCheckedChangeListener { _, checked ->
        overwriteGradlePathDirectory = checked
        overrideInput.first.isEnabled = checked
        overrideInput.second.isEnabled = checked
      }
    }
    packageInput.second.addTextChangedListener { draftSourcePackageName = it?.toString().orEmpty() }
    pathInput.second.addTextChangedListener {
      draftGradlePath = it?.toString().orEmpty()
      if (!overwriteGradlePathDirectory) {
        draftOverrideDirectory = draftGradlePath.trim().trim(':').replace(':', '/')
        overrideInput.second.setText(draftOverrideDirectory)
      }
    }
    overrideInput.second.addTextChangedListener { draftOverrideDirectory = it?.toString().orEmpty() }
    content.addView(packageInput.first)
    content.addView(pathInput.first)
    content.addView(overwriteSwitch)
    content.addView(overrideInput.first)
    content.addView(text("Language", 14f, secondary = true).apply { setPadding(0, dp(12), 0, dp(4)) })
    val languages = ChipGroup(requireContext()).apply { isSingleSelection = true; isSelectionRequired = true }
    lateinit var kotlinLanguageChip: Chip
    lateinit var javaLanguageChip: Chip
    kotlinLanguageChip = chip("Kotlin", moduleLanguage == ModuleSourceLanguage.KOTLIN) {
       if (moduleLanguage != ModuleSourceLanguage.KOTLIN) {
         moduleLanguage = ModuleSourceLanguage.KOTLIN
        kotlinLanguageChip.isChecked = true
        javaLanguageChip.isChecked = false
      }
    }
    javaLanguageChip = chip("Java", moduleLanguage == ModuleSourceLanguage.JAVA) {
       if (moduleLanguage != ModuleSourceLanguage.JAVA) {
         moduleLanguage = ModuleSourceLanguage.JAVA
        kotlinLanguageChip.isChecked = false
        javaLanguageChip.isChecked = true
      }
    }
    languages.addView(kotlinLanguageChip)
    languages.addView(javaLanguageChip)
    content.addView(languages)
    content.addView(text("Gradle DSL", 14f, secondary = true).apply { setPadding(0, dp(12), 0, dp(4)) })
    val dsl = ChipGroup(requireContext()).apply { isSingleSelection = true; isSelectionRequired = true }
    lateinit var kotlinDslChip: Chip
    lateinit var groovyDslChip: Chip
    kotlinDslChip = chip("Kotlin", useKotlinDsl) {
      if (!useKotlinDsl) {
        useKotlinDsl = true
        kotlinDslChip.isChecked = true
        groovyDslChip.isChecked = false
      }
    }
    groovyDslChip = chip("Groovy", !useKotlinDsl) {
      if (useKotlinDsl) {
        useKotlinDsl = false
        kotlinDslChip.isChecked = false
        groovyDslChip.isChecked = true
      }
    }
    dsl.addView(kotlinDslChip)
    dsl.addView(groovyDslChip)
    content.addView(dsl)
    content.addView(bottomActions("Back", "Next") {
      val path = pathInput.second.text.toString().trim()
      val sourcePackageName = packageInput.second.text.toString().trim()
      when {
        !isValidPath(path) -> {
          pathInput.first.error = "Use a valid Gradle path such as :feature:profile"
        }
        !isValidPackageName(sourcePackageName) -> {
          packageInput.first.error = "Use a valid $packageInputLabel such as com.example.profile"
        }
        overwriteGradlePathDirectory && !isValidDirectoryPath(draftOverrideDirectory) -> {
          overrideInput.first.error = "Use a project-relative directory such as feature/profile"
        }
        else -> {
          draftSourcePackageName = sourcePackageName
          draftGradlePath = path
          showWizard(3)
        }
      }
    })
  }

  private fun renderWizardPreview(content: LinearLayout) {
    val path = ModuleCreationRequest.normalizePath(draftGradlePath)
    if (path == null) {
      content.addView(text("Use a valid Gradle path such as :feature:profile.", 14f, secondary = true))
      content.addView(bottomActions("Back", "Close") { showModuleList() })
      return
    }
    val applications = applicationProjects.orEmpty()
    if (selectedApplicationPath !in applications) selectedApplicationPath = applications.firstOrNull()

    if (applications.isNotEmpty()) {
      val consumerContent = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        addView(sectionTitle("Consumer module", topPadding = 0))
        val choices = ChipGroup(requireContext()).apply { isSingleSelection = true }
        applications.forEach { applicationPath ->
          choices.addView(chip(applicationPath, applicationPath == selectedApplicationPath) {
            selectedApplicationPath = applicationPath
            render()
          })
        }
        addView(choices)
      }
      content.addView(previewCard(consumerContent))
    }

    val request = creationRequest(path, selectedApplicationPath) ?: return
    val sourcePackageLabel = if (request.kind == ModuleCreationKind.ANDROID_LIBRARY) "Namespace" else "Package name"
    val configurationContent = LinearLayout(requireContext()).apply {
      orientation = LinearLayout.VERTICAL
      addView(sectionTitle("Configuration", topPadding = 0))
      addView(previewInfo("Type", request.kind.displayName()))
      addView(previewInfo("Source", request.sourceLanguage.name.lowercase().replaceFirstChar { it.uppercase() }))
      addView(previewInfo("Gradle DSL", request.buildDsl.name.lowercase().replaceFirstChar { it.uppercase() }))
      addView(previewInfo(sourcePackageLabel, request.sourcePackageName))
      addView(previewInfo("Module directory", request.moduleDirectory.relativeTo(request.projectRoot).path))
    }
    content.addView(previewCard(configurationContent))

    val changesContent = LinearLayout(requireContext()).apply {
      orientation = LinearLayout.VERTICAL
      addView(sectionTitle("Changes", topPadding = 0))
      addView(text("· ${request.settingsFileName}", 14f, secondary = true))
      addView(text("    + include(\"${request.gradlePath}\")", 14f, secondary = true))
      request.applicationProject?.let { info ->
        val appBuild = File(info.buildFile).relativeToOrNull(request.projectRoot)?.path ?: info.buildFile
        addView(text("· $appBuild", 14f, secondary = true))
        addView(text("    + implementation(project(\"${request.gradlePath}\"))", 14f, secondary = true))
      }
      addView(text("+ ${request.moduleDirectory.relativeTo(request.projectRoot).path}/${request.moduleBuildFileName}", 14f, secondary = true))
      val sourceDirectory = if (request.sourceLanguage == ModuleSourceLanguage.KOTLIN) "kotlin" else "java"
      val sourceFile = if (request.sourceLanguage == ModuleSourceLanguage.KOTLIN) "Sample.kt" else "Sample.java"
      addView(text("+ ${request.moduleDirectory.relativeTo(request.projectRoot).path}/src/main/$sourceDirectory/.../$sourceFile", 14f, secondary = true))
      if (request.kind == ModuleCreationKind.ANDROID_LIBRARY) {
        addView(text("+ ${request.moduleDirectory.relativeTo(request.projectRoot).path}/proguard-rules.pro", 14f, secondary = true))
        addView(text("+ ${request.moduleDirectory.relativeTo(request.projectRoot).path}/consumer-rules.pro", 14f, secondary = true))
      }
    }
    content.addView(previewCard(changesContent))
    content.addView(bottomActions("Back", "Create and sync") { createModule(path, selectedApplicationPath) })
  }

  private fun ModuleCreationKind.displayName() = when (this) {
    ModuleCreationKind.ANDROID_LIBRARY -> "Android library"
    ModuleCreationKind.JAVA_LIBRARY -> "JVM library"
  }

  private fun renderModuleDetail() {
    val module = selectedModule ?: run { showModuleList(animated = false); return }
    binding.moduleToolbar.visibility = View.GONE
    binding.moduleContent.removeAllViews()
    val scroll = scrollContent()
    val content = scrollBody(scroll)
    content.addView(backToolbar(action = { showModuleList() }, module = module))
    val tabs = TabLayout(
      ContextThemeWrapper(requireContext(), R.style.AppTheme_ModuleDetailTabContext),
    )
    listOf("Overview", "Build", "Dependencies").forEach { tabs.addTab(tabs.newTab().setText(it)) }
    content.addView(tabs)
    val detail = LinearLayout(requireContext()).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(0, dp(16), 0, 0)
    }
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
    info(content, "Type", moduleSubtitle(module).substringBefore(" ·"))
    info(content, "Directory", module.projectDir.relativeToOrSelf(projectRoot()).path)
    if (module is AndroidModule) {
      info(content, "Namespace", module.namespace ?: "Unavailable")
    }
    content.addView(outlinedIconButton("Open build script", R.drawable.ic_open_file) { openBuildScript(module) })
    content.addView(iconButton("Sync project", R.drawable.ic_sync) { syncProject() })
  }

  private fun populateBuild(content: LinearLayout, module: GradleProject) {
    if (module is AndroidModule) {
      info(content, "Selected variant", module.configuredVariant?.name ?: "Default")
      info(content, "Available variants", module.variants.joinToString { it.name }.ifEmpty { "Unavailable" })
    } else {
      info(content, "Module type", moduleSubtitle(module))
      content.addView(text("Language target and Gradle configuration are read from the synchronized module model.", 14f, secondary = true))
    }
    content.addView(outlinedIconButton("Open build script", R.drawable.ic_open_file) { openBuildScript(module) })
  }

  private fun populateDependencies(content: LinearLayout, module: GradleProject) {
    val dependencies = when (module) {
      is AndroidModule -> module.getCompileModuleProjects().map { it.path }
      is JavaModule -> module.getCompileModuleProjects().map { it.path }
      is ModuleProject -> module.getCompileModuleProjects().map { it.path }
      else -> emptyList()
    }
    if (dependencies.isEmpty()) content.addView(text("No project dependencies reported by the current workspace.", 14f, secondary = true))
    else dependencies.sorted().forEach { content.addView(text(it, 15f)) }
  }

  private fun createModule(gradlePath: String, applicationPath: String?) {
    val request = creationRequest(gradlePath, applicationPath)
    if (request == null || creatingModule || editorViewModel.isInitializing) return

    creatingModule = true
    render()
    showCheckingDialog()
    lifecycleScope.launch {
      val result = withContext(Dispatchers.IO) {
        moduleCreator.createModule(request)
      }
      if (!isAdded) return@launch
      dismissCreationStatusDialog()
      screen = Screen.LIST
      render()
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

  private fun showCheckingDialog(onCancel: (() -> Unit)? = null) {
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
            .setNegativeButton(if (onCancel == null) null else "Cancel", null)
            .setPositiveButton("Close", null)
            .setCancelable(onCancel != null)
            .create()
            .also { dialog ->
              dialog.setCanceledOnTouchOutside(onCancel != null)
              dialog.setOnCancelListener { onCancel?.invoke() }
              dialog.show()
              dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility = View.GONE
              dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
                visibility = if (onCancel == null) View.GONE else View.VISIBLE
                setOnClickListener {
                  dismissCreationStatusDialog()
                  onCancel?.invoke()
                }
              }
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
    activity.initializeProject()
  }

  // Module type availability is validated by the isolated Gradle probe, not existing modules.

  private fun creationRequest(path: String, consumerPath: String?): ModuleCreationRequest? {
    val normalized = ModuleCreationRequest.normalizePath(path) ?: return null
    val consumer = consumerPath?.let { selected -> applicationProjectDetails.firstOrNull { it.gradlePath == selected } }
    return ModuleCreationRequest(
        gradlePath = normalized,
        kind = moduleType,
        sourceLanguage = moduleLanguage,
        buildDsl = if (useKotlinDsl) GradleDsl.KOTLIN else GradleDsl.GROOVY,
        projectRoot = projectRoot(),
        sourcePackageName = draftSourcePackageName.trim(),
        overrideModuleDirectory = if (overwriteGradlePathDirectory) {
          val candidate = File(projectRoot(), draftOverrideDirectory.trim()).canonicalFile
          candidate.takeIf { it != projectRoot().canonicalFile && it.toPath().startsWith(projectRoot().canonicalFile.toPath()) }
        } else null,
        consumerProjectPath = consumerPath,
        applicationProject = consumer,
    )
  }

  private fun loadApplicationProjectsAndShowWizard() {
    if (creatingModule || editorViewModel.isInitializing) return
    applicationProjectsJob?.cancel()
    showCheckingDialog {
      applicationProjectsJob?.cancel()
      applicationProjectsJob = null
      cancelCurrentToolingBuild()
    }
    creationStatusMessage?.text = "Reading application modules..."
    applicationProjectsJob =
        lifecycleScope.launch {
          val capabilities =
              withContext(Dispatchers.IO) {
                moduleCreator.getProjectCreationCapabilities()
              }
          if (!isAdded) return@launch
          val applications = capabilities?.applicationProjects
           applicationProjectDetails = capabilities?.applicationProjectDetails.orEmpty()
          if (applications == null) {
            // Consumer discovery is optional; the creation probe will validate the module itself.
            dismissCreationStatusDialog()
            applicationProjects = emptyList()
            applicationProjectDetails = emptyList()
            selectedApplicationPath = null
            showWizard(1)
            return@launch
          }
          dismissCreationStatusDialog()
          applicationProjects = applications
          if (selectedApplicationPath !in applications) selectedApplicationPath = applications.firstOrNull()
          showWizard(1)
        }
  }

  private fun cancelCurrentToolingBuild() {
    val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) ?: return
    if (buildService.isToolingServerStarted()) {
      buildService.cancelCurrentBuild()
    }
  }

  private fun showWizard(step: Int) {
    val stepChange = screen == Screen.WIZARD
    val forward = !stepChange || step >= wizardStep
    wizardStep = step
    screen = Screen.WIZARD
    render(animated = true, forward = forward, wizardStepChange = stepChange)
  }

  private fun showModuleList(animated: Boolean = true) {
    selectedModule = null
    screen = Screen.LIST
    wizardScroll = null
    wizardStepContent = null
    wizardStepIndicator = null
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

  private fun previewCard(content: View): View = MaterialCardView(requireContext()).apply {
    radius = dp(8).toFloat()
    cardElevation = dp(1).toFloat()
    setContentPadding(dp(16), dp(8), dp(16), dp(8))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
      bottomMargin = dp(12)
    }
    addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
  }

  private fun previewInfo(label: String, value: String): View = LinearLayout(requireContext()).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, dp(4), 0, dp(4))
    addView(text(label, 13f, secondary = true), LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT))
    addView(text(value, 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
  }

  private fun choiceCard(
      title: String,
      description: String,
      icon: Int,
      selected: Boolean,
      onClick: () -> Unit,
  ): View = MaterialCardView(requireContext()).apply {
    radius = dp(12).toFloat()
    cardElevation = dp(2).toFloat()
    isCheckable = true
    isChecked = selected
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply {
      bottomMargin = dp(12)
    }
    setOnClickListener { onClick() }
    addView(LinearLayout(requireContext()).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(16))
      addView(ImageView(requireContext()).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        contentDescription = null
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
          marginEnd = dp(12)
        }
      })
      addView(LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(title, 16f).apply {
          maxLines = 1
          ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(text(description, 13f, secondary = true).apply {
          maxLines = 1
          ellipsize = android.text.TextUtils.TruncateAt.END
        })
      }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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

  private fun input(
      hint: String,
      value: String,
      startIcon: Int? = null,
      dense: Boolean = false,
      enabled: Boolean = true,
  ): Pair<TextInputLayout, EditText> {
    val inputContext = if (dense) {
      ContextThemeWrapper(requireContext(), com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox_Dense)
    } else {
      requireContext()
    }
    val layout = TextInputLayout(inputContext).apply {
      this.hint = hint
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(12)
      }
    }
    val edit = EditText(requireContext()).apply {
      setText(value)
      inputType = InputType.TYPE_CLASS_TEXT
    }
    layout.addView(edit)
    layout.isEnabled = enabled
    edit.isEnabled = enabled
    startIcon?.let {
      layout.setStartIconDrawable(it)
      layout.refreshDrawableState()
      layout.startIconDrawable?.state = layout.drawableState
    }
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

  private fun iconButton(label: String, icon: Int, action: () -> Unit) =
      MaterialButton(requireContext()).apply {
        text = label
        isAllCaps = false
        setIconResource(icon)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        isEnabled = !creatingModule && !editorViewModel.isInitializing
        setOnClickListener { action() }
      }

  private fun outlinedIconButton(label: String, icon: Int, action: () -> Unit) =
      MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label
        isAllCaps = false
        setIconResource(icon)
        iconSize = dp(18)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        isEnabled = !creatingModule && !editorViewModel.isInitializing
        setOnClickListener { action() }
      }

  private fun backToolbar(
      action: () -> Unit,
      module: GradleProject? = null,
  ) = MaterialToolbar(requireContext()).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
      bottomMargin = dp(4)
    }
    // The parent content is padded 16dp; align the navigation icon's visual start to that edge.
    contentInsetStartWithNavigation = dp(4)
    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
    navigationIcon = requireContext().getDrawable(R.drawable.ic_arrow_back)
    setNavigationIconTint(themeColor(com.google.android.material.R.attr.colorOnSurface))
    navigationContentDescription = "Back to modules"
    isEnabled = !creatingModule && !editorViewModel.isInitializing
    setNavigationOnClickListener {
      if (!creatingModule && !editorViewModel.isInitializing) action()
    }
    title = module?.path.orEmpty()
    if (module != null) {
      menu.add(0, MENU_RENAME_MODULE, 0, "Rename module").apply {
        setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
      }
      menu.add(0, MENU_MOVE_MODULE, 1, "Move module").apply {
        setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
      }
      menu.add(0, MENU_DELETE_MODULE, 2, "Delete module").apply {
        setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
      }
      setOnMenuItemClickListener { item ->
        when (item.itemId) {
          MENU_RENAME_MODULE -> showRenameModuleDialog(module)
          MENU_MOVE_MODULE -> showMoveModuleDialog(module)
          MENU_DELETE_MODULE -> previewModuleDeletion(module)
          else -> return@setOnMenuItemClickListener false
        }
        true
      }
    }
  }

  private fun showRenameModuleDialog(module: GradleProject) {
    val currentPath = input("Current Gradle path", module.path)
    currentPath.second.apply {
      keyListener = null
      isFocusable = false
      isClickable = false
      isLongClickable = false
      isCursorVisible = false
    }
    val newPath = input("New Gradle path", "")
    val moveDirectory = CheckBox(requireContext()).apply {
      text = "Move module directory"
      isChecked = false
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(16)
      }
    }
    val content = LinearLayout(requireContext()).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(24), 0, dp(24), 0)
      addView(currentPath.first)
      addView(newPath.first)
      addView(moveDirectory)
    }
    MaterialAlertDialogBuilder(requireContext())
        .setTitle("Rename module")
        .setView(content)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Next", null)
        .create()
        .also { dialog ->
          dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
              val targetPath = newPath.second.text?.toString().orEmpty().trim()
              if (targetPath.isEmpty()) {
                newPath.first.error = "Enter a Gradle path"
              } else {
                newPath.first.error = null
                dialog.dismiss()
                previewModuleRename(module, targetPath, moveDirectory.isChecked)
              }
            }
          }
          dialog.show()
        }
  }

  private fun showMoveModuleDialog(module: GradleProject) {
    val currentDirectory = input("Current module directory", module.projectDir.relativeToOrSelf(projectRoot()).path)
    currentDirectory.second.apply {
      keyListener = null
      isFocusable = false
      isClickable = false
      isLongClickable = false
      isCursorVisible = false
    }
    val newDirectory = input("New module directory", "")
    val content = LinearLayout(requireContext()).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(24), 0, dp(24), 0)
      addView(currentDirectory.first)
      addView(newDirectory.first)
    }
    MaterialAlertDialogBuilder(requireContext())
        .setTitle("Move module")
        .setView(content)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Next", null)
        .create()
        .also { dialog ->
          dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
              val path = newDirectory.second.text?.toString().orEmpty().trim()
              if (path.isEmpty()) {
                newDirectory.first.error = "Enter a module directory"
              } else {
                newDirectory.first.error = null
                dialog.dismiss()
                previewModuleMove(module, path)
              }
            }
          }
          dialog.show()
        }
  }

  private fun previewModuleMove(module: GradleProject, newDirectoryPath: String) {
    val workspace = IProjectManager.getInstance().getWorkspace()
        ?: return showModuleOperationUnavailable("Project workspace is unavailable.")
    val destination = File(projectRoot(), newDirectoryPath)
    val result = ModuleOperations.planMove(workspace, module.path, destination)
    val message = when (result) {
      is ModuleOperations.MovePlanResult.Ready -> buildString {
        append("Move is ready.\n\n")
        append("Gradle path remains:\n").append(result.plan.gradlePath)
        append("\n\nModule directory:\n")
            .append(result.plan.oldDirectory.relativeToOrSelf(projectRoot()).path)
            .append(" → ")
            .append(result.plan.newDirectory.relativeToOrSelf(projectRoot()).path)
        append("\n\nSettings changes:")
        if (result.plan.removeProjectDirectoryMapping) append("\n• Remove redundant projectDir mapping")
        else append("\n• Update projectDir mapping")
      }
      is ModuleOperations.MovePlanResult.Blocked -> buildString {
        append("Move is blocked:\n\n")
        result.reasons.forEach { append("• ").append(it).append('\n') }
      }
    }
    val dialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle("Move module")
        .setMessage(message)
        .setNegativeButton("Cancel", null)
    if (result is ModuleOperations.MovePlanResult.Ready) {
      dialog.setPositiveButton("Move") { _, _ -> executeModuleMove(module.path, destination) }
    } else {
      dialog.setPositiveButton("Close", null)
    }
    dialog.show()
  }

  private fun executeModuleMove(gradlePath: String, newDirectory: File) {
    if (creatingModule || editorViewModel.isInitializing) return
    val workspace = IProjectManager.getInstance().getWorkspace()
        ?: return showModuleOperationUnavailable("Project workspace is unavailable.")
    val plan = when (val result = ModuleOperations.planMove(workspace, gradlePath, newDirectory)) {
      is ModuleOperations.MovePlanResult.Ready -> result.plan
      is ModuleOperations.MovePlanResult.Blocked -> {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Move module")
            .setMessage(result.reasons.joinToString("\n") { "• $it" })
            .setPositiveButton("Close", null)
            .show()
        return
      }
    }
    creatingModule = true
    render()
    lifecycleScope.launch {
      val result = withContext(Dispatchers.IO) { ModuleOperations.executeMove(plan) }
      if (!isAdded) return@launch
      creatingModule = false
      when (result) {
        is ModuleOperations.MoveExecutionResult.Moved -> {
          IProjectManager.getInstance().notifyFileRenamed(plan.oldDirectory, plan.newDirectory)
          Toast.makeText(requireContext(), "Module moved. Syncing project...", Toast.LENGTH_SHORT).show()
          showModuleList(animated = false)
          syncProject()
        }
        is ModuleOperations.MoveExecutionResult.Failed -> {
          render()
          val rollback = result.rollbackFailures.takeIf { it.isNotEmpty() }
              ?.joinToString("\n") { "• $it" }
              ?.let { "\n\nRollback issues:\n$it" }.orEmpty()
          MaterialAlertDialogBuilder(requireContext())
              .setTitle("Module move failed")
              .setMessage(result.reason + rollback)
              .setPositiveButton("Close", null)
              .show()
        }
      }
    }
  }

  private fun previewModuleRename(module: GradleProject, newGradlePath: String, moveDirectory: Boolean) {
    val workspace = IProjectManager.getInstance().getWorkspace()
        ?: return showModuleOperationUnavailable("Project workspace is unavailable.")
    val result = ModuleOperations.planRename(workspace, module.path, newGradlePath, moveDirectory)
    val message = when (result) {
      is ModuleOperations.RenamePlanResult.Ready -> buildString {
        append("Rename is ready.\n\n")
        append("Gradle path:\n").append(result.plan.oldGradlePath).append(" → ").append(result.plan.newGradlePath)
        if (result.plan.moveDirectory) {
          append("\n\nModule directory:\n")
              .append(result.plan.oldDirectory.relativeToOrSelf(projectRoot()).path)
              .append(" → ")
              .append(result.plan.newDirectory.relativeToOrSelf(projectRoot()).path)
        } else {
          append("\n\nModule directory:\nKeep current location.")
        }
        append("\n\nSettings changes:\n• Rename include")
        when {
          result.plan.renameProjectDirectoryMapping -> append("\n• Rename projectDir mapping")
          result.plan.addProjectDirectoryMapping -> append("\n• Add projectDir mapping")
          result.plan.removeProjectDirectoryMapping -> append("\n• Remove projectDir mapping")
        }
        if (result.plan.dependencyRenames.isNotEmpty()) {
          append("\n\nProject dependencies to update:\n")
          result.plan.dependencyRenames.forEach { rename ->
            append("• ").append(rename.dependentProjectPath).append(": ")
                .append(rename.configuration).append(" → ").append(result.plan.newGradlePath).append('\n')
          }
        }
      }
      is ModuleOperations.RenamePlanResult.Blocked -> buildString {
        append("Rename is blocked:\n\n")
        result.reasons.forEach { append("• ").append(it).append('\n') }
      }
    }
    val dialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle("Rename module")
        .setMessage(message)
        .setNegativeButton("Cancel", null)
    if (result is ModuleOperations.RenamePlanResult.Ready) {
      dialog.setPositiveButton("Rename") { _, _ -> executeModuleRename(module.path, newGradlePath, moveDirectory) }
    } else {
      dialog.setPositiveButton("Close", null)
    }
    dialog.show()
  }

  private fun executeModuleRename(oldGradlePath: String, newGradlePath: String, moveDirectory: Boolean) {
    if (creatingModule || editorViewModel.isInitializing) return
    val workspace = IProjectManager.getInstance().getWorkspace()
        ?: return showModuleOperationUnavailable("Project workspace is unavailable.")
    val plan = when (val result = ModuleOperations.planRename(workspace, oldGradlePath, newGradlePath, moveDirectory)) {
      is ModuleOperations.RenamePlanResult.Ready -> result.plan
      is ModuleOperations.RenamePlanResult.Blocked -> {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rename module")
            .setMessage(result.reasons.joinToString("\n") { "• $it" })
            .setPositiveButton("Close", null)
            .show()
        return
      }
    }
    creatingModule = true
    render()
    lifecycleScope.launch {
      val result = withContext(Dispatchers.IO) { ModuleOperations.executeRename(plan) }
      if (!isAdded) return@launch
      creatingModule = false
      when (result) {
        is ModuleOperations.RenameExecutionResult.Renamed -> {
          if (plan.moveDirectory) {
            IProjectManager.getInstance().notifyFileRenamed(plan.oldDirectory, plan.newDirectory)
          }
          Toast.makeText(requireContext(), "Module renamed. Syncing project...", Toast.LENGTH_SHORT).show()
          showModuleList(animated = false)
          syncProject()
        }
        is ModuleOperations.RenameExecutionResult.Failed -> {
          render()
          val rollback = result.rollbackFailures.takeIf { it.isNotEmpty() }
              ?.joinToString("\n") { "• $it" }
              ?.let { "\n\nRollback issues:\n$it" }.orEmpty()
          MaterialAlertDialogBuilder(requireContext())
              .setTitle("Module rename failed")
              .setMessage(result.reason + rollback)
              .setPositiveButton("Close", null)
              .show()
        }
      }
    }
  }

  private fun previewModuleDeletion(module: GradleProject) {
    val workspace = IProjectManager.getInstance().getWorkspace()
        ?: return showModuleOperationUnavailable("Project workspace is unavailable.")
    val result = ModuleOperations.planDeletion(workspace, module.path)
    val message = when (result) {
      is ModuleOperations.DeletionPlanResult.Ready -> buildString {
        append("Deletion is ready.\n\n")
        append("Deletion can remove ").append(result.plan.target.path).append(" after confirmation.")
        if (result.plan.dependencyRemovals.isNotEmpty()) {
          append("\n\nProject dependencies to remove:\n")
          result.plan.dependencyRemovals.forEach { removal ->
            append("• ").append(removal.dependentProjectPath).append(": ")
                .append(removal.configuration).append(" → ").append(removal.targetProjectPath).append('\n')
          }
        }
      }
      is ModuleOperations.DeletionPlanResult.Blocked -> buildString {
        append("Deletion is blocked:\n\n")
        result.reasons.forEach { append("• ").append(it).append('\n') }
      }
    }
    val dialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle("Delete module")
        .setMessage(message)
        .setNegativeButton("Cancel", null)
    if (result is ModuleOperations.DeletionPlanResult.Ready) {
      dialog.setPositiveButton("Delete") { _, _ -> executeModuleDeletion(module.path) }
    } else {
      dialog.setPositiveButton("Close", null)
    }
    dialog.show()
  }

  private fun executeModuleDeletion(modulePath: String) {
    if (creatingModule || editorViewModel.isInitializing) return
    val workspace = IProjectManager.getInstance().getWorkspace()
        ?: return showModuleOperationUnavailable("Project workspace is unavailable.")
    val plan = when (val result = ModuleOperations.planDeletion(workspace, modulePath)) {
      is ModuleOperations.DeletionPlanResult.Ready -> result.plan
      is ModuleOperations.DeletionPlanResult.Blocked -> {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete $modulePath")
            .setMessage(result.reasons.joinToString("\n") { "• $it" })
            .setPositiveButton("Close", null)
            .show()
        return
      }
    }
    creatingModule = true
    render()
    lifecycleScope.launch {
      val result = withContext(Dispatchers.IO) { ModuleOperations.executeDeletion(plan) }
      if (!isAdded) return@launch
      creatingModule = false
      when (result) {
        is ModuleOperations.DeletionExecutionResult.Deleted -> {
          IProjectManager.getInstance().notifyFileDeleted(plan.target.projectDir)
          Toast.makeText(requireContext(), "Module deleted. Syncing project...", Toast.LENGTH_SHORT).show()
          showModuleList(animated = false)
          syncProject()
        }
        is ModuleOperations.DeletionExecutionResult.Failed -> {
          render()
          val rollback = result.rollbackFailures.takeIf { it.isNotEmpty() }
              ?.joinToString("\n") { "• $it" }
              ?.let { "\n\nRollback issues:\n$it" }.orEmpty()
          MaterialAlertDialogBuilder(requireContext())
              .setTitle("Module deletion failed")
              .setMessage(result.reason + rollback)
              .setPositiveButton("Close", null)
              .show()
        }
      }
    }
  }

  private fun showModuleOperationUnavailable(message: String) {
    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
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

  private fun sectionTitle(value: String, topPadding: Int = 16) = text(value, 18f).apply {
    setPadding(0, dp(topPadding), 0, dp(8))
  }

  private fun info(content: LinearLayout, label: String, value: String) {
    content.addView(text(label, 13f, secondary = true))
    content.addView(text(value, 15f).apply { setPadding(0, 0, 0, dp(12)) })
  }

  private fun projectRoot(): File = IProjectManager.getInstance().projectDir

  private fun usesKotlinSettings(projectRoot: File): Boolean =
      File(projectRoot, "settings.gradle.kts").isFile || !File(projectRoot, "settings.gradle").isFile
  private fun isValidPath(path: String) = path.matches(Regex("^(:[A-Za-z][A-Za-z0-9_-]*)+$"))

  private fun isValidDirectoryPath(path: String): Boolean =
      path.trim().isNotEmpty() && !path.startsWith("/") && !path.contains('\\') &&
          path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9._-]+")) }

  private fun isValidPackageName(value: String): Boolean =
      value.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$"))

  private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

  // Build scripts can be absent or temporarily unreadable while Gradle refreshes the workspace.
  private fun File.readTextSafe(): String = runCatching { readText() }.getOrDefault("")

  override fun onDestroyView() {
    applicationProjectsJob?.cancel()
    applicationProjectsJob = null
    dismissCreationStatusDialog()
    super.onDestroyView()
    _binding = null
  }
  override fun onDestroy() {
    super.onDestroy()
    com.tom.rv2ide.utils.EditorSidebarActions.removeFragmentFromCache("ide.editor.sidebar.moduleManager")
  }

}