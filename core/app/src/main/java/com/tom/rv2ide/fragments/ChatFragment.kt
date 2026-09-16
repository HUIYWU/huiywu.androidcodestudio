package com.tom.rv2ide.fragments

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
import com.google.android.material.textfield.TextInputEditText
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import com.tom.rv2ide.adapters.ChatMessageAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.selectors.ModelChooserPopup
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
    private lateinit var emptyModelChip: Chip
    private lateinit var messageList: RecyclerView
    private lateinit var emptyState: View
    private lateinit var messageAdapter: ChatMessageAdapter

    private lateinit var codeCompletionManager: CodeCompletionManager
    private lateinit var aiRequestHandler: AIRequestHandler

    private var completionStateMonitorJob: Job? = null
    private var isSettingUpCompletion = false

    /**
     * Model dropdown, created on first use.
     *
     * Held so it can be dismissed with the view: the popup keeps a reference to the chip it is
     * anchored to, which would otherwise outlive the chip.
     */
    private var modelChooser: ModelChooserPopup? = null

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
        emptyModelChip = view.findViewById(R.id.emptyModelChip)
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
     * The IME inset is applied as *bottom padding on the root*, not as a translation. The composer is
     * the last child and the transcript above it is weighted, so padding shrinks the transcript and
     * lifts the composer — which also keeps the newest message visible rather than hidden behind the
     * keyboard.
     *
     * The navigation bar height is subtracted because the sidebar host already lifts this page by that
     * amount (see [EditorSidebarFragment], which pads its container for the system bars). The IME inset
     * covers the same strip of screen, so taking it whole would raise the composer twice.
     */
    private fun setupImmersiveInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            applyImePadding(v, insets)
            insets
        }
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
    private fun applyImePadding(view: View, dispatched: WindowInsetsCompat?) {
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

        view.updatePadding(bottom = (ime - hostLift).coerceAtLeast(0))
    }

    /**
     * Wires the model chips and the IME action of the field.
     *
     * The chip is a two-way control: it shows the active model and opens the provider/model dropdown
     * when tapped, so the chat page no longer needs a detour through the settings screen. There are two
     * chips (composer + empty state) and both are kept in sync.
     */
    private fun setupComposer() {
        modelChip.setOnClickListener { showModelChooser(modelChip) }
        emptyModelChip.setOnClickListener { showModelChooser(emptyModelChip) }

        // The model may change on the settings page; both chips follow it.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                aiAgent.currentModelName.collect { model ->
                    renderModelChips(model)
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
     * Opens the model dropdown anchored to the chip that was tapped.
     *
     * A dropdown rather than a dialog: the chip is a control inside the composer, so its choices belong
     * next to it instead of on top of the transcript. There are two chips (composer + empty state) and
     * the list anchors to whichever was used.
     *
     * Refused while a request is in flight: switching provider mid-request would leave the answer being
     * written attributed to a provider that no longer matches it.
     */
    private fun showModelChooser(anchor: View) {
        if (messages.isProcessing.value) {
            showSnackbar(getString(R.string.chat_switch_locked))
            return
        }

        val chooser = modelChooser ?: ModelChooserPopup(requireContext()).also { modelChooser = it }
        chooser.show(anchor) { providerId -> switchToProvider(providerId) }
    }

    /**
     * Activates the chosen provider.
     *
     * `setProvider` already creates the agent and initialises it, and every provider's `initialize()`
     * adopts its own stored model through `Agents.resolveModel`, so the model needs no re-application
     * here. Doing it anyway was actively harmful for Local LLM: `resolveModel` deliberately does not
     * persist its fallback (the model name is chosen on the configuration page and may not appear in
     * that provider's catalogue), so writing the resolved value back would overwrite the user's
     * server-side model name with the default.
     *
     * A failed switch leaves the previous provider running, so the message is a warning rather than a
     * generic error; the most likely cause is a missing API key, which the chooser also signals by
     * refusing the row.
     */
    private fun switchToProvider(providerId: String) {
        if (!aiAgent.setProvider(providerId)) {
            showSnackbar(getString(R.string.chat_switch_failed))
            return
        }

        // The assistant is already open on this provider; refresh what it knows about the project.
        lifecycleScope.launch { aiAgent.setProjectRoot(userRootProject) }
    }

    private fun renderModelChips(model: String) {
        val text = model.ifBlank { getString(R.string.chat_model_unknown) }
        modelChip.text = text
        emptyModelChip.text = text
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
        // The popup holds the chip it is anchored to; left open across a view destruction it would
        // keep that chip (and this fragment's view tree) alive.
        modelChooser?.dismiss()
        modelChooser = null
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