package com.tom.rv2ide.fragments

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import com.tom.rv2ide.adapters.ChatMessageAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
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

    private lateinit var promptLayout: TextInputLayout
    private lateinit var promptInput: TextInputEditText
    private lateinit var sendBtn: MaterialButton
    private lateinit var clearBtn: MaterialButton
    private lateinit var sendProgress: CircularProgressIndicator
    private lateinit var messageList: RecyclerView
    private lateinit var emptyState: View
    private lateinit var messageAdapter: ChatMessageAdapter

    private lateinit var codeCompletionManager: CodeCompletionManager
    private lateinit var aiRequestHandler: AIRequestHandler

    private var completionStateMonitorJob: Job? = null
    private var isSettingUpCompletion = false

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
        promptLayout = view.findViewById(R.id.promptLayout)
        promptInput = view.findViewById(R.id.promptInput)
        sendBtn = view.findViewById(R.id.sendBtn)
        clearBtn = view.findViewById(R.id.clearBtn)
        sendProgress = view.findViewById(R.id.sendProgress)
        messageList = view.findViewById(R.id.messageList)
        emptyState = view.findViewById(R.id.emptyState)
    }

    private fun setupMessageList() {
        messageAdapter = ChatMessageAdapter(onOpenFile = { filePath -> openFileInEditor(filePath) })
        messageList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = messageAdapter
            // The transcript grows at the bottom; without this the first message appears at the top
            // of an otherwise empty list.
            stackFromEnd = true
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
                        sendBtn.isEnabled = !processing
                        sendProgress.visibility = if (processing) View.VISIBLE else View.GONE
                        // Blocked rather than merely unsent: the provider keeps no queue, so a prompt
                        // typed while a request runs would be silently discarded on submit.
                        promptLayout.isEnabled = !processing
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