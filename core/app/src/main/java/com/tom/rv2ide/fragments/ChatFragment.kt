package com.tom.rv2ide.fragments

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ArrayAdapter
import android.widget.ListPopupWindow
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import com.tom.rv2ide.adapters.ChatMessageAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.chat.TranscriptLayoutManager
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.handlers.AIRequestHandler
import com.tom.rv2ide.managers.CodeCompletionManager
import com.tom.rv2ide.fragments.sidebar.AISharedViewModel
import com.tom.rv2ide.utils.ProjectHelper.getProjectRoot
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale

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

        /** Elevation of the context panel above the composer, and the gap left under it, in dp. */
        private const val CONTEXT_PANEL_ELEVATION_DP = 4
        private const val CONTEXT_PANEL_GAP_DP = 4
    }

    private lateinit var promptInput: TextInputEditText
    private lateinit var sendBtn: MaterialButton
    private lateinit var clearBtn: MaterialButton
    private lateinit var modelChip: Chip
    private lateinit var composerContainer: View
    private lateinit var contextIndicator: View
    private lateinit var contextProgress: CircularProgressIndicator
    private lateinit var contextPercent: MaterialTextView

    private var modelMenu: ListPopupWindow? = null
    private var modelMenuOpen = false
    private var contextPanel: PopupWindow? = null

    private lateinit var messageList: RecyclerView
    private lateinit var emptyState: View
    private lateinit var messageAdapter: ChatMessageAdapter

    private lateinit var codeCompletionManager: CodeCompletionManager
    private lateinit var aiRequestHandler: AIRequestHandler

    private var completionStateMonitorJob: Job? = null
    private var isSettingUpCompletion = false

    /** Last IME lift applied to the page, so a layout pass only reacts when it actually changes. */
    private var lastImeLift = 0

    private var imeAnimating = false

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
        composerContainer = view.findViewById(R.id.composerContainer)
        contextIndicator = view.findViewById(R.id.contextIndicator)
        contextProgress = view.findViewById(R.id.contextProgress)
        contextPercent = view.findViewById(R.id.contextPercent)
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
     * The page now fills the sidebar's scroll area directly, so the lift is bottom padding on the
     * page root: padding shortens the transcript instead of growing a scrolled content, which is what
     * it used to do while the host wrapped every page in its own `ScrollView`.
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

        // Follows the keyboard frame by frame. On the layout passes alone the padding lands one
        // frame late and the page visibly trails the keyboard.
        ViewCompat.setWindowInsetsAnimationCallback(
            view,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    imeAnimating = true
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    applyImeLift(view, insets, animated = true, scroll = false)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    imeAnimating = false
                    applyImeLift(view, null)
                }
            }
        )
    }

    /**
     * Lifts the page so the composer clears the keyboard.
     *
     * The window is never resized for the IME (the activity is edge-to-edge), so the lift has to be
     * applied by the content. The page root takes it as bottom padding.
     *
     * The lift is `ime - reservedBelow`, `reservedBelow` being the strip the sidebar keeps under this
     * page (`fragment_editor_sidebar.xml` stacks a 72dp navigation row below the content area). The
     * keyboard covers that strip first, so lifting by the whole inset leaves a gap.
     *
     * [animated] marks a call from the insets animation, where [dispatched] carries the *current frame*
     * of the keyboard. The window's insets already hold the end value there, so mixing the two in a
     * `max` would jump straight to the end on the way up while still animating on the way down.
     */
    private fun applyImeLift(
        view: View,
        dispatched: WindowInsetsCompat?,
        animated: Boolean = false,
        scroll: Boolean = true,
    ) {
        if (view.height == 0) {
            return
        }

        // A layout pass that lands mid-animation reads the window insets, which already hold the end
        // value, and would snap the page to the end position. The animation owns the padding while it
        // runs; `onEnd` re-applies the settled value.
        if (!animated && imeAnimating) {
            return
        }

        fun imeBottom(insets: WindowInsetsCompat?): Int =
            insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

        // Read off the window as well: layouts on the way down consume the insets (`fitsSystemWindows`).
        val fromWindow = view.rootWindowInsets?.let { WindowInsetsCompat.toWindowInsetsCompat(it) }
        val ime = if (animated) {
            imeBottom(dispatched)
        } else {
            maxOf(imeBottom(dispatched), imeBottom(fromWindow))
        }

        val page = IntArray(2)
        view.getLocationOnScreen(page)
        val root = IntArray(2)
        view.rootView.getLocationOnScreen(root)

        val restingBottom = page[1] + view.height
        val reservedBelow = (root[1] + view.rootView.height - restingBottom).coerceAtLeast(0)

        val lift = (ime - reservedBelow).coerceAtLeast(0)
        // Animation frames are applied as they come: the 1px dedup would swallow the small steps at the
        // start of the keyboard animation and then release the whole backlog in one jump.
        if (!animated && lift in (lastImeLift - 1)..(lastImeLift + 1)) {
            return
        }

        lastImeLift = lift
        view.updatePadding(bottom = lift)

        // Only once the keyboard has settled: doing this on every animation frame restarts the list
        // scroll each frame and is what made the movement stutter.
        if (scroll && lift > 0) {
            val count = messageAdapter.itemCount
            if (count > 0) {
                messageList.scrollToPosition(count - 1)
            }
        }
    }

    private fun setupComposer() {
        modelChip.setOnClickListener { toggleModelMenu() }
        contextIndicator.setOnClickListener { toggleContextPanel() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                aiAgent.currentModelName.collect { model ->
                    renderModelChip(model)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                aiAgent.contextUsage.collect { usage -> renderContextUsage(usage) }
            }
        }
    }

    /**
     * Opens the model menu, or closes it when it is already open.
     *
     * The menu hangs from the chip, which stays reachable while it is open, so tapping the chip again
     * is the natural way to close it.
     */
    private fun toggleModelMenu() {
        if (modelMenuOpen) {
            dismissModelMenu()
            return
        }

        if (messages.isProcessing.value) {
            showSnackbar(getString(R.string.chat_switch_locked))
            return
        }

        showModelMenu()
    }

    /**
     * Shows the menu with the models of the provider that is currently in effect.
     *
     * The menu picks a *model*, not a provider: the provider is chosen on the settings page, and this
     * is the same catalogue that page offers for it.
     */
    private fun showModelMenu() {
        val agents = Agents(requireContext())
        val providerId = agents.getProvider()
        val models = agents.getModelsForProvider(providerId).distinct()

        val popup = ListPopupWindow(requireContext()).apply {
            anchorView = modelChip
            isModal = true
            width = menuWidth(models)
            setAdapter(ArrayAdapter(requireContext(), R.layout.item_dropdown_single_line, models))
            setBackgroundDrawable(
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_atc_dropdown_popup)
            )
            setOnItemClickListener { _, _, position, _ ->
                models.getOrNull(position)?.let { model -> switchToModel(providerId, model) }
                dismissModelMenu()
            }
            setOnDismissListener { clearModelMenu() }
        }

        modelMenu = popup
        modelMenuOpen = true
        popup.show()
    }

    private fun dismissModelMenu() {
        val popup = modelMenu
        clearModelMenu()
        popup?.dismiss()
    }

    private fun clearModelMenu() {
        modelMenu = null
        modelMenuOpen = false
    }

    /**
     * Width of the model menu, in pixels.
     *
     * The rows are measured with the same layout the adapter uses, and the menu is never narrower than
     * the chip it hangs from nor wider than the window less its margins.
     */
    private fun menuWidth(models: List<String>): Int {
        val margin = (16 * resources.displayMetrics.density).toInt()
        val row = layoutInflater.inflate(R.layout.item_dropdown_single_line, null, false) as TextView
        // Inflated without a parent, so it has no LayoutParams; TextView.setText() then throws in
        // checkForRelayout() while reading getLayoutParams().width.
        row.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        val widestRow = models.maxOfOrNull { model ->
            row.text = model
            row.measure(spec, spec)
            row.measuredWidth
        } ?: 0

        // coerceIn throws when min > max, and the chip can be wider than the window less the margins.
        val min = modelChip.width.coerceAtLeast(margin)
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

    /**
     * Draws the usage ring: how much of the compression limit the history currently holds.
     *
     * The ring carries the whole number only — the panel carries the sizes — and it turns to the
     * error colour once the history is at or past the limit, the state that makes the next request
     * compress before it runs.
     */
    private fun renderContextUsage(usage: AIAgentManager.ContextUsage) {
        val ratio = if (usage.limitChars <= 0) 0f else usage.usedChars.toFloat() / usage.limitChars
        val percent = (ratio * 100).toInt().coerceAtLeast(0)

        contextProgress.setProgressCompat(percent.coerceAtMost(100), false)
        contextPercent.text = percent.toString()
        contextProgress.setIndicatorColor(
            MaterialColors.getColor(
                contextIndicator,
                if (usage.usedChars > usage.limitChars) R.attr.colorError else R.attr.colorPrimary
            )
        )
    }

    /** "24K" for a round thousand, "12.3K" otherwise; the ring shows a percentage instead. */
    private fun formatChars(chars: Int): String = when {
        chars < 1000 -> chars.toString()
        chars % 1000 == 0 -> "${chars / 1000}K"
        else -> String.format(Locale.US, "%.1fK", chars / 1000f)
    }

    /**
     * Opens the context panel above the composer, or closes it when it is already open.
     *
     * The panel hangs from the composer rather than from the indicator: the composer is the page's
     * bottom edge, so the panel always opens upwards and never has to be flipped.
     */
    private fun toggleContextPanel() {
        if (contextPanel != null) {
            dismissContextPanel()
            return
        }

        val width = composerContainer.width
        if (width == 0) return

        val panelView = layoutInflater.inflate(R.layout.layout_context_panel, null)
        val popup = PopupWindow(panelView, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_atc_dropdown_popup)
            )
            isOutsideTouchable = true
            elevation = CONTEXT_PANEL_ELEVATION_DP * resources.displayMetrics.density
            setOnDismissListener { contextPanel = null }
        }

        bindContextPanel(panelView)

        // Measured before showing: the panel is placed entirely above the composer, and that offset
        // needs its height, which a wrap_content popup only reports once it has been laid out.
        panelView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val gap = (CONTEXT_PANEL_GAP_DP * resources.displayMetrics.density).toInt()
        popup.height = panelView.measuredHeight
        popup.showAsDropDown(composerContainer, 0, -(panelView.measuredHeight + gap))
        contextPanel = popup
    }

    private fun dismissContextPanel() {
        val popup = contextPanel
        contextPanel = null
        popup?.dismiss()
    }

    /** Fills the panel: the current usage, the threshold slider, and the manual compression action. */
    private fun bindContextPanel(panelView: View) {
        val agents = Agents(requireContext())
        val usageText: MaterialTextView = panelView.findViewById(R.id.contextPanelUsage)
        val slider: Slider = panelView.findViewById(R.id.contextPanelSlider)
        val compress: MaterialButton = panelView.findViewById(R.id.contextPanelCompress)
        val hint: View = panelView.findViewById(R.id.contextPanelHint)

        fun renderUsage() {
            val usage = aiAgent.contextUsage.value
            usageText.text = getString(
                R.string.chat_context_usage_format,
                formatChars(usage.usedChars),
                formatChars(usage.limitChars)
            )
        }

        renderUsage()

        slider.value = agents.getContextCharLimit().toFloat()
        slider.setLabelFormatter { value -> formatChars(value.toInt()) }
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            agents.setContextCharLimit(value.toInt())
            aiAgent.refreshContextUsage()
            renderUsage()
        }

        val canCompress = aiAgent.canCompressContext()
        compress.isEnabled = canCompress
        hint.visibility = if (canCompress) View.GONE else View.VISIBLE
        compress.setOnClickListener {
            dismissContextPanel()
            aiRequestHandler.compressNow()
        }
    }

    private fun setupMessageList() {
        messageAdapter = ChatMessageAdapter(onOpenFile = { filePath -> openFileInEditor(filePath) })

        val listLayoutManager = TranscriptLayoutManager(requireContext())

        messageList.apply {
            layoutManager = listLayoutManager
            adapter = messageAdapter
            // The list is rebuilt wholesale on every update; the default cross-fade reads as flicker.
            itemAnimator = null
        }
    }

    /**
     * Whether the transcript is scrolled to its end.
     *
     * The list only follows new output while it is already at the end: following unconditionally
     * would drag the view back down every time a delta arrives, so a row the user had opened and
     * scrolled away from would start moving under them.
     */
    private fun isListAtBottom(): Boolean {
        val layoutManager = messageList.layoutManager ?: return true
        if (messageList.childCount == 0) return true
        val lastVisible = messageList.getChildAt(messageList.childCount - 1) ?: return true
        val lastIndex = layoutManager.getPosition(lastVisible)
        return lastIndex == messageAdapter.itemCount - 1 &&
            lastVisible.bottom <= messageList.height - messageList.paddingBottom
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
            messages = messages,
            interruptedText = getString(R.string.chat_tool_call_interrupted)
        )
    }

    private fun setupListeners() {
        // One slot, two modes: while a request runs the button interrupts it instead.
        sendBtn.setOnClickListener {
            if (messages.isProcessing.value) {
                aiRequestHandler.cancel()
            } else {
                submitPrompt()
            }
        }

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
                        val wasAtBottom = isListAtBottom()
                        messageAdapter.submitList(items) {
                            if (wasAtBottom && items.isNotEmpty()) {
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
                        // stacked surfaces. The button itself stays tappable: while a request runs it
                        // is the interrupt control.
                        if (processing) {
                            sendBtn.setIconResource(R.drawable.ic_disconnect)
                            sendBtn.contentDescription = getString(R.string.chat_stop)
                        } else {
                            sendBtn.setIconResource(R.drawable.ic_send)
                            sendBtn.contentDescription = getString(R.string.send)
                        }
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
        aiRequestHandler.execute(userRequest, getCurrentFile()?.absolutePath)
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
        // Interrupt before wiping: a request left running would keep writing into the store.
        aiRequestHandler.cancel()
        lifecycleScope.launch {
            try {
                codeCompletionManager.clearSuggestion()
                aiAgent.clearConversation()
                messages.clear()
                messageAdapter.resetRowState()
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
        dismissModelMenu()
        dismissContextPanel()
        imeAnimating = false
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