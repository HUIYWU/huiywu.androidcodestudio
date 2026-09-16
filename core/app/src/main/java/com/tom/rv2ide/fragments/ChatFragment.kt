package com.tom.rv2ide.fragments

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import com.tom.rv2ide.adapters.ChatMessageAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.handlers.AIRequestHandler
import com.tom.rv2ide.managers.CodeCompletionManager
import com.tom.rv2ide.fragments.sidebar.AISharedViewModel
import com.tom.rv2ide.utils.ProjectHelper.getProjectRoot
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Chat page of the AI sidebar.
 *
 * The transcript lives in [AISharedViewModel] rather than in this fragment, so answers survive view
 * recreation. This class therefore only wires the list, the composer and code completion; the
 * request lifecycle belongs to [AIRequestHandler].
 */
class ChatFragment : Fragment() {

    private val sharedViewModel: AISharedViewModel by activityViewModels()
    private val aiAgent: AIAgentManager get() = sharedViewModel.aiAgent

    private val messages get() = sharedViewModel.chatMessages

    companion object {
        private val log = LoggerFactory.getLogger(ChatFragment::class.java)

        private const val PREFS_NAME = "ai_preferences"
        private const val KEY_COMPLETION_ENABLED = "code_completion_enabled"
    }

    private lateinit var promptInput: TextInputEditText
    private lateinit var sendBtn: MaterialButton
    private lateinit var clearBtn: MaterialButton
    private lateinit var modelChip: Chip

    private lateinit var modelDropdown: MaterialAutoCompleteTextView

    private lateinit var messageList: RecyclerView
    private lateinit var emptyState: View
    private lateinit var messageAdapter: ChatMessageAdapter

    private lateinit var codeCompletionManager: CodeCompletionManager
    private lateinit var aiRequestHandler: AIRequestHandler

    private var completionStateMonitorJob: Job? = null
    private var isSettingUpCompletion = false

    /** Last IME lift applied to the page, so a layout pass only reacts when it actually changes. */
    private var lastImeLift = 0

    /**
     * Project root of the editor.
     *
     * A `get()` rather than a `val`: evaluated once at construction it captured whichever project
     * happened to be open when the sidebar page was first built, and stayed stale afterwards.
     */
    private val userRootProject: String get() = getProjectRoot().absolutePath

    /**
     * Single listener for the completion preference.
     *
     * Replaces the previous pair of listeners (this fragment's, plus a 200 ms polling loop) and the
     * settings screen's 100 ms loop. `getSharedPreferences` hands out one instance per process, so a
     * listener registered here also observes writes made from the settings screen.
     */
    private val sharedPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_COMPLETION_ENABLED) {
            val enabled = completionPrefs().getBoolean(KEY_COMPLETION_ENABLED, true)
            if (IdeLogConfig.shouldLogDebug()) {
                log.debug("Completion preference changed: {}", enabled)
            }
            lifecycleScope.launch { handleCompletionStateChange(enabled) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupMessageList()
        setupComposer()
        setupImmersiveInsets(view)
        setupManagers()
        setupListeners()
        observeState()
        loadProject()
        registerPreferenceListener()
    }

    override fun onResume() {
        super.onResume()
        startCompletionStateMonitoring()
    }

    override fun onPause() {
        super.onPause()
        stopCompletionStateMonitoring()
    }

    private fun initializeViews(view: View) {
        promptInput = view.findViewById(R.id.promptInput)
        sendBtn = view.findViewById(R.id.sendBtn)
        clearBtn = view.findViewById(R.id.clearBtn)
        modelChip = view.findViewById(R.id.modelChip)
        modelDropdown = view.findViewById(R.id.modelDropdown)
        messageList = view.findViewById(R.id.messageList)
        emptyState = view.findViewById(R.id.emptyState)
    }

    /**
     * Keeps the composer above the on-screen keyboard.
     *
     * The host activity calls `enableEdgeToEdge()` + `setDecorFitsSystemWindows(window, false)`, so the
     * window is never resized for the IME and nothing moves by default — the keyboard simply drew over
     * the field. `windowSoftInputMode` cannot fix that: once edge-to-edge is on, the insets have to be
     * consumed by the content.
     *
     * The sidebar host wraps this page in a `ScrollView`, so the lift cannot be bottom padding on the
     * root: padding only makes the scrolling content taller, leaving the field exactly where it was.
     * The whole page is translated up instead, which moves the composer clear of the keyboard.
     */
    private fun setupImmersiveInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            applyImeLift(v, insets)
            insets
        }

        // Safety net. The activity's own layouts declare `fitsSystemWindows`, and a view that consumes
        // the insets also stops dispatching them, so the listener above is not guaranteed to fire. A
        // global layout pass always happens when the keyboard is shown or hidden, and the window's own
        // insets (read inside applyImeLift) are correct regardless of what was dispatched here.
        view.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    applyImeLift(view, null)
                }
            }
        )
    }

    /**
     * Lifts the page so the composer clears the keyboard.
     *
     * The window is never resized for the IME (the activity is edge-to-edge), and the host wraps this
     * page in a `ScrollView`, where bottom padding only makes the scrolling content taller. The page is
     * translated instead.
     *
     * The lift is `ime - reservedBelow`, `reservedBelow` being the strip the sidebar keeps under this
     * page (`fragment_editor_sidebar.xml` stacks a 72dp navigation row below the scroll area). The
     * keyboard covers that strip first, so lifting by the whole inset leaves a gap.
     */
    private fun applyImeLift(view: View, dispatched: WindowInsetsCompat?) {
        if (view.height == 0) {
            return
        }

        fun imeBottom(insets: WindowInsetsCompat?): Int =
            insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

        // Read off the window as well: layouts on the way down consume the insets (`fitsSystemWindows`).
        val fromWindow = view.rootWindowInsets?.let { WindowInsetsCompat.toWindowInsetsCompat(it) }
        val ime = maxOf(imeBottom(dispatched), imeBottom(fromWindow))

        val page = IntArray(2)
        view.getLocationOnScreen(page)
        val root = IntArray(2)
        view.rootView.getLocationOnScreen(root)

        // getLocationOnScreen includes the translation applied below, so that translation is removed
        // again to get the resting bottom the reserved strip is measured against.
        val restingBottom = page[1] + view.height - view.translationY.toInt()
        val reservedBelow = (root[1] + view.rootView.height - restingBottom).coerceAtLeast(0)

        val lift = (ime - reservedBelow).coerceAtLeast(0)
        if (lift in (lastImeLift - 1)..(lastImeLift + 1)) {
            return
        }

        lastImeLift = lift
        view.translationY = -lift.toFloat()

        if (lift > 0) {
            val count = messageAdapter.itemCount
            if (count > 0) {
                messageList.scrollToPosition(count - 1)
            }
        }
    }

    private fun setupComposer() {
        modelChip.setOnClickListener { toggleModelMenu() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                aiAgent.currentModelName.collect { model ->
                    renderModelChip(model)
                }
            }
        }
    }

    /**
     * Opens or closes the model menu.
     *
     * A toggle rather than a plain `showDropDown()`: the menu drops *below* the composer, so the chip
     * stays reachable while the menu is open and tapping it again is the natural way to close it. A
     * plain open would stack a second menu every time the chip was tapped.
     */
    private fun toggleModelMenu() {
        if (modelDropdown.isPopupShowing) {
            modelDropdown.dismissDropDown()
            return
        }

        if (messages.isProcessing.value) {
            showSnackbar(getString(R.string.chat_switch_locked))
            return
        }

        populateModelMenu(modelDropdown)
        modelDropdown.post { modelDropdown.showDropDown() }
    }

    /**
     * Fills the menu with the models of the provider that is currently in effect.
     *
     * The menu picks a *model*, not a provider: the provider is chosen on the settings page, and this
     * is the same catalogue that page offers for it.
     */
    private fun populateModelMenu(dropdown: MaterialAutoCompleteTextView) {
        val agents = Agents(requireContext())
        val providerId = agents.getProvider()

        val models = agents.getModelsForProvider(providerId).distinct()

        dropdown.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_dropdown_single_line, models)
        )
        dropdown.threshold = 0
        dropdown.dropDownWidth = menuWidth(dropdown, models)

        dropdown.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            models.getOrNull(position)?.let { model -> switchToModel(providerId, model) }
        }
    }

    /**
     * Width of the model menu popup, in pixels.
     *
     * The popup does not size itself from its rows: `MaterialAutoCompleteTextView` only uses the
     * content width to grow the *field* (`onMeasure`, AT_MOST), and an `AutoCompleteTextView` with no
     * explicit width takes the popup width from its anchor — here the transparent field behind the
     * chip, which is a few characters wide.
     *
     * So the widest row is measured instead, with the same item layout the adapter uses, and clamped
     * to the window.
     */
    private fun menuWidth(dropdown: MaterialAutoCompleteTextView, models: List<String>): Int {
        val margin = (16 * resources.displayMetrics.density).toInt()
        val row = layoutInflater.inflate(R.layout.item_dropdown_single_line, null, false) as TextView
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        val widestRow = models.maxOfOrNull { model ->
            row.text = model
            row.measure(spec, spec)
            row.measuredWidth
        } ?: 0

        // coerceIn throws when min > max, and the field can be wider than the window less the margins.
        val min = dropdown.width.coerceAtLeast(margin)
        val max = (resources.displayMetrics.widthPixels - margin * 2).coerceAtLeast(min)

        return widestRow.coerceIn(min, max)
    }

    /**
     * Switches the model inside the provider that is already active.
     *
     * The provider is deliberately left alone: `Agents.setModel` writes the model under its own
     * provider key without touching the selection, and `reinitializeWithSelectedModel` then
     * re-initialises the running agent with it — the same path the settings page takes. Re-selecting
     * the provider here instead would turn the chip into a second, competing provider selector.
     */
    private fun switchToModel(providerId: String, model: String) {
        Agents(requireContext()).setModel(providerId, model)
        aiAgent.reinitializeWithSelectedModel()

        // The agent was re-initialised, so it no longer holds the project it was given.
        lifecycleScope.launch { aiAgent.setProjectRoot(userRootProject) }
    }

    private fun renderModelChip(model: String) {
        modelChip.text = model.ifBlank { getString(R.string.chat_model_unknown) }
    }

    private fun setupMessageList() {
        messageAdapter = ChatMessageAdapter(onOpenFile = { filePath -> openFileInEditor(filePath) })

        // `stackFromEnd` belongs to the LayoutManager, not to the RecyclerView. The transcript grows
        // at the bottom, so without it the first message sits at the top of an otherwise empty
        // viewport.
        val listLayoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }

        messageList.apply {
            layoutManager = listLayoutManager
            adapter = messageAdapter
            // The list is rebuilt wholesale on every update; the default cross-fade reads as flicker.
            itemAnimator = null
        }
    }

    private fun setupManagers() {
        codeCompletionManager = CodeCompletionManager.getInstance(
            requireContext(),
            lifecycleScope,
            aiAgent
        )

        aiRequestHandler = AIRequestHandler(
            lifecycleScope = lifecycleScope,
            aiAgent = aiAgent,
            messages = messages
        )
    }

    private fun setupListeners() {
        sendBtn.setOnClickListener { submitPrompt() }

        clearBtn.setOnClickListener { clearConversation() }
    }

    /**
     * Mirrors the transcript into the list and keeps the send button in sync with the request state.
     */
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    messages.messages.collect { items ->
                        messageAdapter.submitList(items) {
                            if (items.isNotEmpty()) {
                                messageList.scrollToPosition(items.size - 1)
                            }
                        }
                        emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    messages.isProcessing.collect { processing ->
                        // No spinner in the composer any more: the transcript's own status row (see
                        // item_chat_status.xml) already shows progress, and a second indicator inside
                        // the field was one of the things that made the bottom block read as two
                        // stacked surfaces. The button stays disabled either way.
                        sendBtn.isEnabled = !processing
                        modelChip.isEnabled = !processing
                        // Blocked rather than merely unsent: the provider keeps no queue, so a prompt
                        // typed while a request runs would be silently discarded on submit.
                        promptInput.isEnabled = !processing
                    }
                }
            }
        }
    }

    private fun submitPrompt() {
        val userRequest = promptInput.text.toString()

        if (userRequest.isBlank()) {
            showSnackbar(getString(R.string.chat_empty_prompt))
            return
        }

        if (messages.isProcessing.value) {
            return
        }

        promptInput.text?.clear()
        codeCompletionManager.clearSuggestion()
        aiRequestHandler.execute(userRequest)
    }

    private fun registerPreferenceListener() {
        completionPrefs().registerOnSharedPreferenceChangeListener(sharedPrefsListener)
    }

    private fun unregisterPreferenceListener() {
        completionPrefs().unregisterOnSharedPreferenceChangeListener(sharedPrefsListener)
    }

    private fun completionPrefs(): SharedPreferences =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private suspend fun handleCompletionStateChange(enabled: Boolean) {
        if (IdeLogConfig.shouldLogDebug()) {
            log.debug("handleCompletionStateChange: {}", enabled)
        }

        if (enabled) {
            setupCodeCompletionForCurrentFile()
        } else {
            codeCompletionManager.cleanup()
        }
    }

    /**
     * Re-applies the completion preference when the page comes back to the foreground.
     *
     * Replaces a 200 ms polling loop: the preference is re-read at the only moments it can have
     * changed behind this fragment's back, which is while it was not resumed.
     */
    private fun startCompletionStateMonitoring() {
        stopCompletionStateMonitoring()

        completionStateMonitorJob = lifecycleScope.launch {
            val enabled = completionPrefs().getBoolean(KEY_COMPLETION_ENABLED, true)
            if (enabled) {
                setupCodeCompletionForCurrentFile()
            }
        }
    }

    private fun stopCompletionStateMonitoring() {
        completionStateMonitorJob?.cancel()
        completionStateMonitorJob = null
    }

    private fun loadProject() {
        lifecycleScope.launch {
            try {
                val success = aiAgent.setProjectRoot(userRootProject)

                if (success) {
                    showSnackbar("✦ Project loaded: ${userRootProject.substringAfterLast("/")}")
                } else {
                    showSnackbar(getString(R.string.chat_project_load_failed))
                }
            } catch (e: Exception) {
                showSnackbar("Error: ${e.message}")
            }
        }
    }

    private fun setupCodeCompletionForCurrentFile() {
        if (isSettingUpCompletion) {
            if (IdeLogConfig.shouldLogDebug()) {
                log.debug("Already setting up, skipping")
            }
            return
        }

        if (!completionPrefs().getBoolean(KEY_COMPLETION_ENABLED, true)) {
            if (IdeLogConfig.shouldLogDebug()) {
                log.debug("Code completion is disabled, skipping setup")
            }
            return
        }

        isSettingUpCompletion = true

        lifecycleScope.launch {
            val editor = getCurrentEditor()
            val suggestionView = getCurrentSuggestionView()

            if (editor != null && suggestionView != null) {
                if (IdeLogConfig.shouldLogDebug()) {
                    log.debug("Setting up code completion")
                }
                codeCompletionManager.setup(
                    editor,
                    suggestionView,
                    onReady = {
                        if (IdeLogConfig.shouldLogDebug()) {
                            log.debug("✦ Code completion ready!")
                        }
                        isSettingUpCompletion = false
                    },
                    onError = { e ->
                        if (IdeLogConfig.shouldLogError()) {
                            log.error("✗ Completion setup failed: {}", e.message, e)
                        }
                        isSettingUpCompletion = false
                    }
                )
            } else {
                if (IdeLogConfig.shouldLogWarn()) {
                    log.warn("Editor or SuggestionView is null, cannot setup")
                }
                isSettingUpCompletion = false
            }
        }
    }

    fun getCodeCompletionManager(): CodeCompletionManager {
        return codeCompletionManager
    }

    private fun openFileInEditor(filePath: String) {
        lifecycleScope.launch {
            try {
                // The agent reports absolute paths, so trust the path itself first; the name lookup
                // is only a fallback for responses that carried a bare file name.
                val file = File(filePath)
                    .takeIf { it.exists() && it.isFile }
                    ?: findFileInProject(File(userRootProject), File(filePath).name)

                if (file == null) {
                    showSnackbar("File not found: ${File(filePath).name}")
                    return@launch
                }

                val activity = requireActivity()
                if (activity is EditorHandlerActivity) {
                    activity.openFile(file)
                }
            } catch (e: Exception) {
                showSnackbar("Error opening file: ${e.message}")
            }
        }
    }

    private fun findFileInProject(projectRoot: File, fileName: String): File? {
        if (!projectRoot.exists() || !projectRoot.isDirectory) {
            return null
        }

        return projectRoot.walkTopDown().firstOrNull {
            it.isFile && it.name == fileName
        }
    }

    fun clearConversation() {
        lifecycleScope.launch {
            try {
                codeCompletionManager.clearSuggestion()
                aiAgent.clearConversation()
                messages.clear()
                promptInput.text?.clear()

                showSnackbar(getString(R.string.chat_cleared))
            } catch (e: Exception) {
                showSnackbar("Error clearing: ${e.message}")
            }
        }
    }

    private fun getCurrentEditor() = try {
        val activity = requireActivity()
        if (activity is EditorHandlerActivity) {
            activity.getCurrentEditor()?.editor
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun getCurrentFile() = try {
        val activity = requireActivity()
        if (activity is EditorHandlerActivity) {
            activity.getCurrentEditor()?.file
        } else null
    } catch (e: Exception) {
        null
    }

    private fun getCurrentSuggestionView() = try {
        val activity = requireActivity()
        if (activity is EditorHandlerActivity) {
            activity.getCurrentEditor()?.suggestionView
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun showSnackbar(message: String) {
        val anchorView = activity?.findViewById<View>(android.R.id.content)
            ?: view
            ?: return

        Snackbar.make(anchorView, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        completionStateMonitorJob?.cancel()
        // No popup is dismissed here: the model menus belong to their AutoCompleteTextView, so the
        // framework tears them down with the view tree.
        // Guarded because setupManagers() is not guaranteed to have run: when onViewCreated() throws
        // before reaching it, the view is still destroyed and this callback still fires, and reading
        // an uninitialized lateinit on the way out would replace the real error with a misleading
        // UninitializedPropertyAccessException.
        if (::aiRequestHandler.isInitialized) {
            aiRequestHandler.cancel()
        }
        unregisterPreferenceListener()
        super.onDestroyView()
    }
}