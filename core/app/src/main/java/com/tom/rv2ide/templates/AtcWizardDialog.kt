package com.tom.rv2ide.templates

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment as AndroidEnvironment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.provider.DocumentsContractCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialSharedAxis
import com.tom.androidcodestudio.project.manager.builder.LanguageType
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.FolderPickerActivity
import com.tom.rv2ide.activities.IDEConfigurations
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.databinding.DialogAtcWizardBinding
import com.tom.rv2ide.templates.android.Template
import com.tom.rv2ide.templates.android.TemplateOptions
import com.tom.rv2ide.templates.android.TemplateRegistry
import com.tom.rv2ide.templates.android.etc.NativeCpp.Check
import com.tom.rv2ide.templates.preferences.Options
import com.tom.rv2ide.templates.preferences.WizardPreferences
import com.tom.rv2ide.utils.Environment
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
class AtcWizardDialog : BottomSheetDialogFragment() {

  private var activeDropdownPopup: ListPopupWindow? = null


  private var listener: AtcInterface.TemplateCreationListener? = null
  private var selectedTemplate: Template? = null
  private var _binding: DialogAtcWizardBinding? = null
  private val binding
    get() = _binding!!

  fun init(listener: AtcInterface.TemplateCreationListener?) {
    this.listener = listener
  }

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    val dialog = BottomSheetDialog(requireContext(), theme)
    val ctx = requireContext()

    _binding =
        DataBindingUtil.inflate(LayoutInflater.from(ctx), R.layout.dialog_atc_wizard, null, false)

    setupSwitches()
    setupInputs(ctx)
    setupTemplatesGrid(ctx)
    setupButtons(ctx)
    setPageInteractive(binding.pageTemplates, true)
    setPageInteractive(binding.pageOptions, false)

    dialog.setContentView(binding.root)
    dialog.setOnShowListener { configureBottomSheetForFullExpansion(dialog) }
    return dialog
  }

  private fun setupSwitches() {
    with(binding) {
      useCMakeSwitch.visibility = View.GONE
      useCMakeSwitch.isChecked = Options.OPT_BUILD_SYSTEM_USE_CMAKE
      useKtsSwitch.isChecked = Options.OPT_USE_GRADLE_KTS

      useCMakeSwitch.setOnCheckedChangeListener { _, isChecked ->
        Options.OPT_BUILD_SYSTEM_USE_CMAKE = isChecked
        if (isChecked) validateAndSelectCMake()
      }

      useKtsSwitch.setOnCheckedChangeListener { _, isChecked ->
        Options.OPT_USE_GRADLE_KTS = isChecked
      }

      ndkVersionButton.visibility = View.GONE
      ndkVersionButton.setOnClickListener { showNdkVersionPicker(requireContext()) }
    }
  }

  private fun setupInputs(ctx: Context) {
    val lastSaveLocation = WizardPreferences.getLastSaveLocation(ctx)
    binding.saveLocationInput.setText(lastSaveLocation ?: Environment.PROJECTS_DIR.absolutePath)

    binding.projectNameInput.addTextChangedListener(
        SimpleTextWatcher {
          updatePackageNameFromProject(it)
          validateProjectName()
        }
    )

    binding.saveLocationInput.addTextChangedListener(SimpleTextWatcher { validateProjectName() })

    binding.saveLocationLayout.setEndIconOnClickListener {
      (activity as? FragmentActivity)?.let { act ->
        FolderPickerActivity.onFolderPicked = { uriStr ->
          val path = SafResolver.resolveToPath(ctx, uriStr)
          binding.saveLocationInput.setText(path)
          WizardPreferences.setLastSaveLocation(ctx, path)
        }
        act.startActivity(Intent(act, FolderPickerActivity::class.java))
      }
    }

    setupDropdowns(ctx)
  }

  private fun setupDropdowns(ctx: Context) {
    val languageItems = arrayOf(ctx.getString(R.string.kotlin), ctx.getString(R.string.java))
    var selectedLanguageIndex = 0
    binding.languageInput.applyDropdownFieldStyle()
    binding.languageInput.setText(languageItems[selectedLanguageIndex], false)
    updateLanguageIcon(languageItems[selectedLanguageIndex])
    val showLanguageDropdown = {
showAtcDropdown(
            anchor = binding.languageInput,
          items = languageItems,
          selectedIndexProvider = { selectedLanguageIndex },
      ) { position ->
        selectedLanguageIndex = position.coerceIn(languageItems.indices)
        binding.languageInput.setText(languageItems[selectedLanguageIndex], false)
        updateLanguageIcon(languageItems[selectedLanguageIndex])
      }
    }
    binding.languageInput.setOnClickListener { showLanguageDropdown() }
    binding.languageInputLayout.setEndIconOnClickListener { showLanguageDropdown() }

    val sdkValues = Sdk.values()
    val minSdkDisplay = sdkValues.map { it.displayName() }.toTypedArray()
    val defIdx = sdkValues.indexOfFirst { it.api == 21 }.coerceAtLeast(0)
    var selectedMinSdkIndex = defIdx
    Options.OPT_MIN_SDK = sdkValues.getOrNull(defIdx)?.api ?: 21
    binding.minSdkInput.applyDropdownFieldStyle()
    binding.minSdkInput.setText(minSdkDisplay[selectedMinSdkIndex], false)
    val showMinSdkDropdown = {
showAtcDropdown(
            anchor = binding.minSdkInput,
          items = minSdkDisplay,
          selectedIndexProvider = { selectedMinSdkIndex },
      ) { position ->
        selectedMinSdkIndex = position.coerceIn(minSdkDisplay.indices)
        binding.minSdkInput.setText(minSdkDisplay[selectedMinSdkIndex], false)
        Options.OPT_MIN_SDK = sdkValues.getOrNull(selectedMinSdkIndex)?.api ?: 21
      }
    }
    binding.minSdkInput.setOnClickListener { showMinSdkDropdown() }
    binding.minSdkInputLayout.setEndIconOnClickListener { showMinSdkDropdown() }

    val nativeLangValues = arrayOf("C++", "C")
    var selectedNativeLanguageIndex = 0
    binding.nativeLanguageInput.applyDropdownFieldStyle()
    binding.nativeLanguageInput.setText(nativeLangValues[selectedNativeLanguageIndex], false)
    updateNativeLanguageIcon(nativeLangValues[selectedNativeLanguageIndex])
    val showNativeLanguageDropdown = {
showAtcDropdown(
            anchor = binding.nativeLanguageInput,
          items = nativeLangValues,
          selectedIndexProvider = { selectedNativeLanguageIndex },
      ) { position ->
        selectedNativeLanguageIndex = position.coerceIn(nativeLangValues.indices)
        val selected = nativeLangValues[selectedNativeLanguageIndex]
        binding.nativeLanguageInput.setText(selected, false)
        Options.OPT_NATIVE_LANGUAGE = if (selectedNativeLanguageIndex == 1) "c" else "cpp"
        updateNativeLanguageIcon(selected)
      }
    }
    binding.nativeLanguageInput.setOnClickListener { showNativeLanguageDropdown() }
    binding.nativeLanguageInputLayout.setEndIconOnClickListener { showNativeLanguageDropdown() }
  }

  private fun MaterialAutoCompleteTextView.applyDropdownFieldStyle() {
    keyListener = null
    inputType = android.text.InputType.TYPE_NULL
    isFocusable = false
    isClickable = true
  }

  private fun showAtcDropdown(
      anchor: MaterialAutoCompleteTextView,
      items: Array<String>,
      selectedIndexProvider: () -> Int,
      onSelected: (Int) -> Unit,
  ) {
    activeDropdownPopup?.dismiss()
    val adapter = createDropdownAdapter(items, selectedIndexProvider)
    val popup =
        ListPopupWindow(requireContext()).apply {
          anchorView = anchor
          isModal = true
          setAdapter(adapter)
          setBackgroundDrawable(
              ContextCompat.getDrawable(requireContext(), R.drawable.bg_atc_dropdown_popup)
          )
          verticalOffset = 0
          width = anchor.width
          setOnItemClickListener { _, _, position, _ ->
            onSelected(position)
            dismiss()
          }
          setOnDismissListener {
            if (activeDropdownPopup === this) activeDropdownPopup = null
          }
        }
    activeDropdownPopup = popup
    popup.show()
  }

  private fun createDropdownAdapter(
      items: Array<String>,
      selectedIndexProvider: () -> Int,
  ): ArrayAdapter<String> {
    return object : ArrayAdapter<String>(requireContext(), R.layout.item_atc_dropdown, items) {
      override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent) as TextView
        val selectedIndex = selectedIndexProvider().coerceIn(0, items.lastIndex)
        val backgroundRes =
            if (position != selectedIndex) {
              R.drawable.bg_atc_dropdown_unselected
            } else {
              R.drawable.bg_atc_dropdown_selected_single
            }
        view.background = ContextCompat.getDrawable(requireContext(), backgroundRes)
        return view
      }
    }
  }

  private fun dpToPx(dp: Int): Int {
    return (dp * requireContext().resources.displayMetrics.density).toInt()
  }

  private fun updateLanguageIcon(selected: String) {
    val iconRes =
        if (selected.equals(getString(R.string.java), ignoreCase = true)) {
          R.drawable.ic_language_java
        } else {
          R.drawable.ic_language_kotlin
        }
    binding.languageInputLayout.startIconDrawable =
        ContextCompat.getDrawable(requireContext(), iconRes)
  }

  private fun updateNativeLanguageIcon(selected: String) {
    val iconRes = if (selected.trim().equals("C", ignoreCase = true)) {
      R.drawable.ic_language_c
    } else {
      R.drawable.ic_language_cpp
    }
    binding.nativeLanguageInputLayout.startIconDrawable =
        ContextCompat.getDrawable(requireContext(), iconRes)
  }

  private fun setupTemplatesGrid(ctx: Context) {
    val templates = TemplateRegistry.getAllTemplates()
    binding.templatesGrid.layoutManager = GridLayoutManager(ctx, 2)
    binding.templatesGrid.adapter =
        TemplateAdapter(ctx, templates) { template ->
          selectedTemplate = template
          template.configureOptions()

          if (template.javaClass.simpleName == "NativeCpp") {
            validateNativeTemplate(ctx)
          } else {
            proceedToOptionsPage(ctx)
          }
        }
  }

  private fun setupButtons(ctx: Context) {
    binding.backButton.setOnClickListener {
      binding.root.post {
        hideKeyboardAndClearFocus()
        setPageInteractive(binding.pageOptions, false)
        setPageInteractive(binding.pageTemplates, true)
        SheetTransitions.slide(
            binding.wizardContainer,
            binding.pageOptions,
            binding.pageTemplates,
            MaterialSharedAxis.X,
            false,
        )
        binding.backButton.visibility = View.GONE
        binding.createButton.visibility = View.GONE
        binding.actionsContainer.visibility = View.GONE
      }
    }

    binding.createButton.setOnClickListener { createProject(ctx) }
  }

  private fun validateAndSelectCMake() {
    val cmakeVersions = Check.getAllCMakeVersions()
    if (cmakeVersions.isEmpty()) {
      showAlert(
          getString(R.string.cmake_not_found_title),
          getString(R.string.cmake_not_found_message),
      ) {
        startActivity(Intent(requireContext(), IDEConfigurations::class.java))
      }
      binding.useCMakeSwitch.isChecked = false
    } else {
      showCMakeVersionPicker(requireContext(), cmakeVersions)
    }
  }

  private fun validateNativeTemplate(ctx: Context) {
    val progressDialog =
        showProgress(
            getString(R.string.checking_ndk_title),
            getString(R.string.checking_ndk_message),
        )

    CoroutineScope(Dispatchers.IO).launch {
      val hasNdk = Check.isAtLeastOneInstalled()
      val highestNdk = if (hasNdk) Check.getHighestNdkVersion() else null
      val isValid = highestNdk?.let { Check.validateNdkVersion(it) } ?: false

      withContext(Dispatchers.Main) {
        progressDialog.dismiss()

        when {
          !hasNdk -> showNdkError(ctx, getString(R.string.error_ndk_not_found_or_incompatible))
          !isValid ->
              showNdkError(
                  ctx,
                  getString(R.string.invalid_highest_ndk_message, highestNdk),
              )
          else -> {
            Options.OPT_SELECTED_NDK_VERSION = highestNdk
            proceedToOptionsPage(ctx)
          }
        }
      }
    }
  }

  private fun showNdkError(ctx: Context, message: String) {
    showAlert(getString(R.string.native_error_title), message) {
      startActivity(Intent(ctx, IDEConfigurations::class.java))
    }
  }

  private fun proceedToOptionsPage(ctx: Context) {
    binding.root.post {
      val shouldKeepExpanded = isBottomSheetExpanded()
      val templateName = "My${selectedTemplate?.displayName?.replace(" ", "")}" ?: "MyProject"
      val packageSuffix =
          "my${selectedTemplate?.displayName?.replace(" ", ".")?.lowercase()}" ?: "myproject"

      binding.projectNameInput.setText(templateName)
      binding.packageNameInput.setText("com.example.$packageSuffix")

      val isNative = Options.OPT_IS_NATIVE_CPP
      binding.useCMakeSwitch.visibility = if (isNative) View.VISIBLE else View.GONE
      binding.nativeLanguageInputLayout.visibility = if (isNative) View.VISIBLE else View.GONE
      binding.ndkVersionButton.visibility = if (isNative) View.VISIBLE else View.GONE
      binding.ndkVersionButton.text =
          getString(R.string.ndk_version_selected, Options.OPT_SELECTED_NDK_VERSION ?: getString(R.string.auto))

      setPageInteractive(binding.pageTemplates, false)
      setPageInteractive(binding.pageOptions, true)
      SheetTransitions.slide(
          binding.wizardContainer,
          binding.pageTemplates,
          binding.pageOptions,
          MaterialSharedAxis.X,
          true,
      )
      binding.backButton.visibility = View.VISIBLE
      binding.createButton.visibility = View.VISIBLE
      binding.actionsContainer.visibility = View.VISIBLE
      if (shouldKeepExpanded) {
        expandBottomSheet()
      }
    }
  }

  private fun configureBottomSheetForFullExpansion(dialog: BottomSheetDialog) {
    val bottomSheet =
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
    bottomSheet.layoutParams =
        bottomSheet.layoutParams.apply {
          height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    val behavior = BottomSheetBehavior.from(bottomSheet)
    behavior.isFitToContents = false
    behavior.expandedOffset = 0
    behavior.skipCollapsed = false
  }

  private fun isBottomSheetExpanded(): Boolean {
    val behavior = bottomSheetBehavior() ?: return false
    return behavior.state == BottomSheetBehavior.STATE_EXPANDED
  }

  private fun expandBottomSheet() {
    binding.root.post {
      bottomSheetBehavior()?.apply {
        isFitToContents = false
        expandedOffset = 0
        state = BottomSheetBehavior.STATE_EXPANDED
      }
    }
  }

  private fun bottomSheetBehavior(): BottomSheetBehavior<View>? {
    val bottomSheetDialog = dialog as? BottomSheetDialog ?: return null
    val bottomSheet =
        bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return null
    return BottomSheetBehavior.from(bottomSheet)
  }

  private fun hideKeyboardAndClearFocus() {
    val focused = dialog?.currentFocus ?: binding.root.findFocus()
    focused?.clearFocus()
    val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow((focused ?: binding.root).windowToken, 0)
  }

  private fun setPageInteractive(view: View, enabled: Boolean) {
    view.isEnabled = enabled
    if (view is ViewGroup) {
      for (index in 0 until view.childCount) {
        setPageInteractive(view.getChildAt(index), enabled)
      }
    }
  }

  private fun createProject(ctx: Context) {
    val proj =
        binding.projectNameInput.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            ?: selectedTemplate?.displayName?.replace(" ", "")
            ?: "MyProject"
    val pkg =
        binding.packageNameInput.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            ?: "com.example.${selectedTemplate?.displayName?.replace(" ", ".")?.lowercase() ?: "myproject"}"

    var lang =
        if (binding.languageInput.text?.toString()?.lowercase()?.startsWith("java") == true)
            LanguageType.JAVA
        else LanguageType.KOTLIN

    val sdkValues = Sdk.values()
    val minSdkDisplay = sdkValues.map { it.displayName() }.toTypedArray()
    val selectedIdx = minSdkDisplay.indexOf(binding.minSdkInput.text?.toString()).coerceAtLeast(0)
    val sdkApi = Options.OPT_MIN_SDK ?: 21
    
    val savePath =
        binding.saveLocationInput.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            ?: Environment.PROJECTS_DIR.absolutePath
    val projectDir = File(savePath, proj)

    if (projectDir.exists()) {
      showAlert(
          getString(R.string.project_already_exists_title),
          getString(R.string.project_already_exists_message, proj),
      )
      return
    }

    if (
        Options.OPT_IS_NATIVE_GAME_ACTIVITY == true &&
            WizardPreferences.getLastSaveLocation(ctx) != Environment.AT_ACSHOME_PROJECTS.toString()
    ) {
      requireAcsHomeProjectsDir()
      return
    }

    WizardPreferences.setLastSaveLocation(ctx, savePath)
    WizardPreferences.addRecentProject(ctx, projectDir.absolutePath)

    selectedTemplate?.let { t ->
      if (t.javaClass.simpleName.contains("Compose", ignoreCase = true)) {
        lang = LanguageType.KOTLIN
      }

      CoroutineScope(Dispatchers.Main).launch {
        try {
          t.create(
              ctx,
              listener,
              TemplateOptions(
                  proj,
                  pkg,
                  lang,
                  sdkApi,
                  Options.OPT_USE_GRADLE_KTS,
                  Options.OPT_BUILD_SYSTEM_USE_CMAKE,
                  File(savePath),
              ),
          )
        } catch (e: Exception) {
          listener?.onTemplateCreated(false, "Error: ${e.message}")
        }
      }
    } ?: listener?.onCreationCancelled()

    dismiss()
  }

  private fun requireAcsHomeProjectsDir() {
    showAlert(
        getString(R.string.invalid_save_location_title),
        getString(R.string.invalid_save_location_message),
        getString(R.string.automatically_switch),
    ) {
      binding.saveLocationInput.setText(Environment.AT_ACSHOME_PROJECTS.toString())
      WizardPreferences.setLastSaveLocation(
          requireContext(),
          Environment.AT_ACSHOME_PROJECTS.toString(),
      )
    }
  }

  private fun validateProjectName() {
    val projectName = binding.projectNameInput.text?.toString()?.trim().orEmpty()
    val saveLocation = binding.saveLocationInput.text?.toString()?.trim().orEmpty()

    if (projectName.isEmpty()) {
      binding.projectNameLayout.error = null
      return
    }

    val projectDir = File(saveLocation, projectName)

    when {
      projectDir.exists() -> {
        binding.projectNameLayout.error = getString(R.string.project_name_exists_error)
        binding.createButton.isEnabled = false
      }
      !projectName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*$")) -> {
        binding.projectNameLayout.error = getString(R.string.project_name_invalid_error)
        binding.createButton.isEnabled = false
      }
      else -> {
        binding.projectNameLayout.error = null
        binding.createButton.isEnabled = true
      }
    }
  }

  private fun updatePackageNameFromProject(projectName: CharSequence?) {
    val current = binding.packageNameInput.text?.toString()?.trim().orEmpty()
    if (current.isNotEmpty() && current.contains('.')) {
      val segs = current.split('.').toMutableList()
      segs[segs.lastIndex] =
          projectName
              ?.toString()
              ?.trim()
              ?.lowercase()
              ?.replace("[^a-zA-Z0-9_]".toRegex(), "")
              ?.ifEmpty { "app" } ?: "app"
      binding.packageNameInput.setText(segs.joinToString("."))
    }
  }

  private fun showNdkVersionPicker(ctx: Context) {
    val versions = Check.getAllNdkVersions()
    if (versions.isEmpty()) {
      showAlert(getString(R.string.no_ndk_found_title), getString(R.string.no_ndk_found_message))
      return
    }

    val versionLabels =
        versions.map { "$it ${if (Check.validateNdkVersion(it)) "✓" else "✗"}" }.toTypedArray()
    val currentIndex = versions.indexOf(Options.OPT_SELECTED_NDK_VERSION).coerceAtLeast(0)

    MaterialAlertDialogBuilder(ctx)
        .setTitle(R.string.select_ndk_version_title)
        .setSingleChoiceItems(versionLabels, currentIndex) { dialog, which ->
          val selectedVersion = versions[which]
          if (Check.validateNdkVersion(selectedVersion)) {
            Options.OPT_SELECTED_NDK_VERSION = selectedVersion
            binding.ndkVersionButton.text = getString(R.string.ndk_version_selected, selectedVersion)
            dialog.dismiss()
          } else {
            Toast.makeText(ctx, getString(R.string.invalid_ndk_message, selectedVersion), Toast.LENGTH_SHORT).show()
          }
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
  }

  private fun showCMakeVersionPicker(ctx: Context, versions: List<String>) {
    val versionLabels =
        versions
            .map { "$it ${if (Check.validateCMakeVersion(it) != null) "✓" else "✗"}" }
            .toTypedArray()

    MaterialAlertDialogBuilder(ctx)
        .setTitle(R.string.select_cmake_version_title)
        .setSingleChoiceItems(versionLabels, 0) { dialog, which ->
          val selectedVersion = versions[which]
          Check.validateCMakeVersion(selectedVersion)?.let { path ->
            Options.OPT_CMAKE_PATH = path
            Toast.makeText(ctx, getString(R.string.cmake_selected_message, selectedVersion), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
          } ?: Toast.makeText(ctx, getString(R.string.invalid_cmake_message, selectedVersion), Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton(R.string.cancel) { _, _ -> binding.useCMakeSwitch.isChecked = false }
        .show()
  }

  private fun showAlert(
      title: String,
      message: String,
      positiveText: String = "",
      onPositive: (() -> Unit)? = null,
  ) {
    val resolvedPositive = positiveText.ifEmpty { getString(R.string.action_ok) }
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(resolvedPositive) { _, _ -> onPositive?.invoke() }
        .setNegativeButton(R.string.cancel, null)
        .show()
  }

  private fun showProgress(title: String, message: String) =
      MaterialAlertDialogBuilder(requireContext())
          .setTitle(title)
          .setMessage(message)
          .setCancelable(false)
          .create()
          .apply { show() }

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }
}

class TemplateAdapter(
    private val ctx: Context,
    private val templates: List<Template>,
    private val onTemplateClick: (Template) -> Unit,
) : RecyclerView.Adapter<TemplateVH>() {

  companion object {
    private val log = LoggerFactory.getLogger(TemplateAdapter::class.java)
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TemplateVH {
    val displayMetrics = ctx.resources.displayMetrics
    val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
    if (IdeLogConfig.shouldLogDebug()) {
      log.debug("Screen Width DP: {}", screenWidthDp)
    }
    
    val card =
        MaterialCardView(ctx).apply {
          layoutParams =
              ViewGroup.MarginLayoutParams(
                      ViewGroup.LayoutParams.MATCH_PARENT,
                      ViewGroup.LayoutParams.WRAP_CONTENT,
                  )
                  .apply { setMargins(8.dp, 8.dp, 8.dp, 8.dp) }
          radius = 20.dp.toFloat()
          isClickable = true
          isFocusable = true
          strokeWidth = 0
          elevation = 1.dp.toFloat()
        }

    val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val title =
        TextView(ctx).apply {
          textSize = 14f
          setPadding(12.dp, 12.dp, 12.dp, 8.dp)
          gravity = android.view.Gravity.CENTER
        }
    
    val image =
        ImageView(ctx).apply {
          scaleType = ImageView.ScaleType.CENTER_CROP
          
          if (screenWidthDp >= 600) {
            if (IdeLogConfig.shouldLogDebug()) {
              log.debug("Using small size for large screen")
            }
            layoutParams = LinearLayout.LayoutParams(100.dp, 100.dp).apply {
              gravity = android.view.Gravity.CENTER
            }
          } else {
            if (IdeLogConfig.shouldLogDebug()) {
              log.debug("Using normal size")
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 160.dp)
          }
        }

    layout.addView(title)
    layout.addView(image)
    card.addView(layout)
    return TemplateVH(card, title, image)
  }

  override fun onBindViewHolder(holder: TemplateVH, position: Int) {
    val template = templates[position]
    holder.title.text = template.displayName

    val resId =
        ctx.resources.getIdentifier(
            template.javaClass.simpleName.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase(),
            "drawable",
            ctx.packageName,
        )
    holder.image.setImageResource(if (resId != 0) resId else android.R.drawable.ic_menu_gallery)
    holder.card.setOnClickListener { onTemplateClick(template) }
  }

  override fun getItemCount() = templates.size
}

class TemplateVH(val card: MaterialCardView, val title: TextView, val image: ImageView) :
    RecyclerView.ViewHolder(card)

class SimpleTextWatcher(private val afterChanged: (CharSequence?) -> Unit) :
    android.text.TextWatcher {
  override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

  override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

  override fun afterTextChanged(s: android.text.Editable?) = afterChanged(s)
}

private val Int.dp: Int
  get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

internal object SafResolver {
  private const val ANDROID_DOCS_AUTHORITY = "com.android.externalstorage.documents"
  private const val ANDROIDIDE_DOCS_AUTHORITY = "com.tom.rv2ide.documents"

  fun resolveToPath(context: Context, uriStr: String): String {
    return try {
      val uri = Uri.parse(uriStr)
      val docUri =
          DocumentsContractCompat.buildDocumentUriUsingTree(
              uri,
              DocumentsContractCompat.getTreeDocumentId(uri)!!,
          ) ?: return Environment.PROJECTS_DIR.absolutePath

      val docId =
          DocumentsContractCompat.getDocumentId(docUri)
              ?: return Environment.PROJECTS_DIR.absolutePath

      when (docUri.authority) {
        ANDROIDIDE_DOCS_AUTHORITY -> docId
        ANDROID_DOCS_AUTHORITY -> {
          val split = docId.split(':')
          if (split.size != 2) return Environment.PROJECTS_DIR.absolutePath

          if (split[0] == "primary") {
            File(AndroidEnvironment.getExternalStorageDirectory(), split[1]).absolutePath
          } else {
            "/storage/${split[0]}/${split[1]}"
          }
        }
        else -> Environment.PROJECTS_DIR.absolutePath
      }
    } catch (e: Exception) {
      e.printStackTrace()
      Environment.PROJECTS_DIR.absolutePath
    }
  }
}
