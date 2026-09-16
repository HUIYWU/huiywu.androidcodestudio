package com.tom.rv2ide.fragments

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
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

    /**
     * Invisible field that owns the model menu.
     *
     * The chip is laid over it and the chip's click opens this field's dropdown. Letting the
     * `AutoCompleteTextView` own the popup is what keeps the menu a standard Material one — its
     * placement, width and up/down flip stay in the framework rather than being computed here.
     */
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
     * Applies the composer's IME lift, tolerating insets that were consumed upstream.
     *
     * The editor activity's own layouts declare `fitsSystemWindows`, which consumes the system window
     * insets (the IME included) on the way down, so the insets handed to this fragment are frequently
     * zero even with the keyboard open — that is why the field used to stay put instead of rising.
     * The window's own insets are therefore read as well and the larger of the two is used. It is the
     * same fallback [getSystemBarInsets] uses for the system bars.
     */
    private fun applyImeLift(view: View, dispatched: WindowInsetsCompat?) {
        val fromWindow = view.rootWindowInsets?.let { WindowInsetsCompat.toWindowInsetsCompat(it) }

        fun bottom(insets: WindowInsetsCompat?, type: Int): Int =
            insets?.getInsets(type)?.bottom ?: 0

        val ime = maxOf(
            bottom(dispatched, WindowInsetsCompat.Type.ime()),
            bottom(fromWindow, WindowInsetsCompat.Type.ime())
        )

        // Matches exactly how the host measured its own lift, so the two cannot disagree by a pixel.
        val hostLift = fromWindow
            ?.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            ?.bottom
            ?: bottom(dispatched, WindowInsetsCompat.Type.navigationBars())

        // The host already raised this page by the navigation bar's height; the IME inset covers the
        // same strip of screen, so taking it whole would lift the composer one bar too far.
        val lift = (ime - hostLift).coerceAtLeast(0)

        if (lift != lastImeLift) {
            lastImeLift = lift
            view.translationY = -lift.toFloat()

            // Only on a change, so the repeated global-layout passes of a single keyboard animation do
            // not keep re-scrolling the transcript. The list is clipped from the top, so this keeps the
            // newest message in view rather than pushing it under the composer.
            if (lift > 0) {
                val count = messageAdapter.itemCount
                if (count > 0) {
                    messageList.scrollToPosition(count - 1)
                }
            }
        }
    }

    /**
     * Wires the model chip, the IME action of the field and the keyboard lift.
     *
     * The chip is the control the user sees and taps; a transparent, exposed-dropdown field is laid
     * under it and the chip's click opens that field's menu. Letting the `AutoCompleteTextView` own the
     * popup is what makes the menu a standard Material dropdown — its placement, width and up/down flip
     * stay in the framework rather than being recomputed here.
     */
    private fun setupComposer() {
        modelChip.setOnClickListener { toggleModelMenu() }

        // The model may change on the settings page; the chip follows it.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                aiAgent.currentModelName.collect { model ->
                    renderModelChip(model)
                }
            }
        }


        // Enter sends; Shift+Enter still inserts a newline. The field is multi-line, so without this
        // the only way to send is the button, and the soft keyboard's action key does nothing useful.
        promptInput.setOnEditorActionListener { _, actionId, event ->
            val isSend = actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && !event.isShiftPressed)
            if (isSend) {
                submitPrompt()
                true
            } else {
                false
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

        // Rebuilt on every open: the catalogue of the active provider can have been refreshed on the
        // settings page since this page was built, and so can the selected model.
        populateModelMenu(modelDropdown)
        // Posted, exactly like ProviderConfigDialog: the popup refuses to open while its adapter is
        // still being replaced, and one frame later there is always something to show.
        modelDropdown.post { modelDropdown.showDropDown() }
    }

    /**
     * Fills the menu with the models of the provider that is currently in effect.
     *
     * The menu picks a *model*, not a provider — the provider is chosen on the settings page, and this
     * is the same list that page offers for it. The stored model is checked; a model that was fetched
     * from the provider but is not the selected one is still listed and still selectable.
     */
    private fun populateModelMenu(dropdown: MaterialAutoCompleteTextView) {
        val agents = Agents(requireContext())
        val providerId = agents.getProvider()
        val currentModel = agents.getModel(providerId)

        val models = agents.getModelsForProvider(providerId).distinct()

        val labels = models.map { model ->
            val checkmark = if (model == currentModel) "\u2713" else "\u00a0"
            "$checkmark $model"
        }

        dropdown.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_dropdown_single_line, labels)
        )
        // Without this the popup filters itself against the text in the field, which is one of the
        // entries, leaving a single row to choose from.
        dropdown.threshold = 0

        // The click reports a row index, so the click listener is bound to the very list it was built
        // from rather than re-reading it (which could have shifted underneath).
        dropdown.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            models.getOrNull(position)?.let { model -> switchToModel(providerId, model) }
        }
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