/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.catalog.ModelRepository
import kotlinx.coroutines.launch

/**
 * Shared editor for a provider's endpoint, credentials and model.
 *
 * Every provider is configured the same way — credentials, then the model to use — and the model
 * list is always fetched from the provider rather than hard-coded. The providers differ only in
 * where the values live and whether the endpoint is user-supplied, so those are the hooks; the
 * dialog layout, validation, fetch flow and save flow are identical and live here.
 *
 * Subclasses only override the hooks they need and must remain instantiable by the framework, so
 * nothing is passed through a constructor.
 */
abstract class ModelConfigDialog : DialogFragment() {

    private var baseUrlLayout: TextInputLayout? = null
    private var baseUrlInput: TextInputEditText? = null
    private var apiKeyLayout: TextInputLayout? = null
    private var apiKeyInput: TextInputEditText? = null
    private var modelDropdownLayout: TextInputLayout? = null
    private var modelDropdown: MaterialAutoCompleteTextView? = null
    private var fetchButton: MaterialButton? = null
    private var fetchProgress: LinearProgressIndicator? = null

    /** Models reported by the provider during this dialog's lifetime. */
    private var fetchedModels: List<String> = emptyList()

    /**
     * Endpoint [fetchedModels] came from.
     *
     * Tracked so the list is only published when it still describes the endpoint being saved: the
     * user can fetch from one server and then edit the URL to another, and caching the first
     * server's models against the second URL would make the picker list models that cannot be used.
     */
    private var fetchedFrom: String? = null

    /**
     * Invoked after the values have been written.
     *
     * Held as a settable property rather than a constructor argument so the dialog stays
     * instantiable by the framework, and only survives as long as the dialog it belongs to.
     */
    var onSaved: (() -> Unit)? = null

    // ------------------------------------------------------------------ hooks

    /** Provider whose catalogue the fetch button queries. */
    protected abstract val providerId: String

    /** Dialog title. */
    protected abstract val titleRes: Int

    /** Hint for the API key field. */
    protected abstract val apiKeyHintRes: Int

    /**
     * Hint for the endpoint field, or `null` when the provider has a fixed endpoint.
     *
     * Returning `null` hides the field: offering an editable endpoint for a provider that ignores
     * it would suggest a setting that does not exist.
     */
    protected open val baseUrlHintRes: Int? = null

    /** Whether an empty API key blocks saving. Local servers are commonly unauthenticated. */
    protected open val isApiKeyRequired: Boolean = true

    /** Model currently stored for this provider, or `null` when nothing usable is stored. */
    protected open fun readModel(): String? {
        val agents = Agents(requireContext())
        // The stored model belongs to whichever provider is active, so it is only this provider's
        // model when the two agree.
        return if (agents.getProvider() == providerId) agents.getAgent() else null
    }

    /** Falls back to the provider's declared default when nothing is stored. */
    protected open fun defaultModel(): String =
        Agents(requireContext()).getDefaultModelForProvider(providerId)

    protected open fun readBaseUrl(): String? = null
    protected open fun storeBaseUrl(value: String) = Unit

    protected open fun readApiKey(): String? = null
    protected open fun storeApiKey(value: String) = Unit

    /** Called after validation succeeds, so it can assume a non-blank model. */
    protected open fun storeModel(value: String) = Unit

    /**
     * Fetches the provider's catalogue.
     *
     * The default goes through the repository, which resolves the provider's endpoint and caches the
     * result. Providers whose endpoint the user supplies override this, because their catalogue has
     * to be fetched from the URL being edited rather than from the stored one.
     */
    protected open suspend fun fetchCatalogue(
        baseUrl: String?,
        apiKey: String?
    ): ModelRepository.RefreshResult = ModelRepository.refresh(providerId, apiKey)

    // ------------------------------------------------------------- lifecycle

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_model_config, null)

        baseUrlLayout = view.findViewById(R.id.baseUrlLayout)
        baseUrlInput = view.findViewById(R.id.baseUrlInput)
        apiKeyLayout = view.findViewById(R.id.apiKeyLayout)
        apiKeyInput = view.findViewById(R.id.apiKeyInput)
        modelDropdownLayout = view.findViewById(R.id.modelDropdownLayout)
        modelDropdown = view.findViewById(R.id.modelDropdown)
        fetchButton = view.findViewById(R.id.fetchModelsButton)
        fetchProgress = view.findViewById(R.id.fetchProgress)

        applyHints(context)
        loadSavedConfig()

        fetchButton?.setOnClickListener { fetchModels() }

        return MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(view)
            .setPositiveButton(R.string.action_save) { _, _ -> save() }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
    }

    /**
     * Applies the per-provider labels.
     *
     * Done from code rather than in the layout so a single layout serves every provider; duplicating
     * it per provider is what let their dialogs drift apart in the first place.
     */
    private fun applyHints(context: Context) {
        val baseUrlHint = baseUrlHintRes
        if (baseUrlHint == null) {
            baseUrlLayout?.visibility = View.GONE
        } else {
            baseUrlLayout?.hint = context.getString(baseUrlHint)
        }

        apiKeyLayout?.hint = context.getString(apiKeyHintRes)
        modelDropdownLayout?.hint = context.getString(R.string.model_config_model_hint)
    }

    private fun loadSavedConfig() {
        baseUrlLayout?.takeIf { it.visibility == View.VISIBLE }?.let {
            baseUrlInput?.setText(readBaseUrl().orEmpty())
        }
        apiKeyInput?.setText(readApiKey().orEmpty())

        // A previously fetched catalogue is preferred: it holds names the provider has actually
        // reported. Without one the stored or declared default is shown, so the dialog is usable
        // before anything has been fetched.
        val stored = readModel()
        val model = stored
            ?.takeIf { it.isNotBlank() }
            ?: ModelRepository.getModels(providerId).firstOrNull()
            ?: defaultModel()

        setModelOptions(listOf(model), model)
    }

    /** Replaces the dropdown contents, keeping [selected] as the current value. */
    private fun setModelOptions(models: List<String>, selected: String?) {
        val dropdown = modelDropdown ?: return
        // A non-empty list is required: an empty adapter would clear the field and the saved value
        // would silently look like "no model".
        val options = models.filter { it.isNotBlank() }.ifEmpty { listOf(defaultModel()) }
        // Without this the popup filters itself against the text in the field, which is one of the
        // entries, leaving a single line to choose from.
        dropdown.threshold = 0
        dropdown.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_dropdown_single_line, options)
        )
        dropdown.setText(
            selected?.takeIf { it.isNotBlank() } ?: options.first(),
            // Without this the TextView is treated as free text and the selection is not committed.
            false
        )
    }

    // ----------------------------------------------------------------- fetch

    private fun fetchModels() {
        val context = context ?: return
        val baseUrl = if (baseUrlLayout?.visibility == View.VISIBLE) {
            baseUrlInput?.text?.toString()?.trim().orEmpty().also {
                if (it.isEmpty()) {
                    baseUrlInput?.error = getString(R.string.model_config_base_url_required)
                    return
                }
            }
        } else {
            null
        }

        val apiKey = apiKeyInput?.text?.toString()?.trim().orEmpty().takeIf { it.isNotEmpty() }
        if (isApiKeyRequired && apiKey == null) {
            apiKeyLayout?.error = getString(R.string.model_config_api_key_required)
            return
        }
        apiKeyLayout?.error = null

        // Resolved before the spinner is shown: returning after enabling it would leave the button
        // disabled with a progress bar stuck on screen.
        val host = activity as? FragmentActivity ?: return

        hideKeyboard()
        setFetching(true)

        host.lifecycleScope.launch {
            val result = fetchCatalogue(baseUrl, apiKey)
            // The dialog may have been dismissed while the request was in flight.
            if (!isAdded) return@launch

            setFetching(false)
            when (result) {
                is ModelRepository.RefreshResult.Success -> {
                    fetchedModels = result.models
                    fetchedFrom = baseUrl
                    val current = modelDropdown?.text?.toString()?.trim()
                    val selected = current?.takeIf { it in result.models } ?: result.models.first()
                    setModelOptions(result.models, selected)
                    toast(context.getString(R.string.local_llm_fetch_loaded, result.models.size))
                    // The list was just replaced, so open it: that is what the button was pressed for.
                    showModelDropdown()
                }

                is ModelRepository.RefreshResult.Failure -> {
                    // Keep whatever is on screen: a failed refresh must not wipe the model name.
                    toast(context.getString(R.string.local_llm_fetch_failed, result.reason))
                }
            }
        }
    }

    /**
     * Shows the model popup.
     *
     * Called after a successful fetch, where the list has just been replaced and the user is
     * expected to pick from it; showing it automatically saves a second tap. Posting the call is
     * necessary because the popup refuses to open while the adapter is still being updated.
     */
    private fun showModelDropdown() {
        modelDropdown?.post { modelDropdown?.showDropDown() }
    }

    // ------------------------------------------------------------------ save

    private fun save() {
        val baseUrl = if (baseUrlLayout?.visibility == View.VISIBLE) {
            baseUrlInput?.text?.toString()?.trim().orEmpty().also {
                if (it.isEmpty()) {
                    baseUrlInput?.error = getString(R.string.model_config_base_url_required)
                    return
                }
            }
        } else {
            null
        }

        val apiKey = apiKeyInput?.text?.toString()?.trim().orEmpty()
        if (isApiKeyRequired && apiKey.isEmpty()) {
            apiKeyLayout?.error = getString(R.string.model_config_api_key_required)
            return
        }
        apiKeyLayout?.error = null

        // The model field is a dropdown, so the value comes from the text it displays; when nothing
        // was fetched it still holds the stored or default name.
        val model = modelDropdown?.text?.toString()?.trim().orEmpty()
            .ifEmpty { readModel() ?: defaultModel() }

        baseUrl?.let { storeBaseUrl(it) }
        storeApiKey(apiKey)
        storeModel(model)

        // Make the fetched list reusable by the sidebar picker — but only while it still describes
        // the endpoint being saved.
        if (fetchedModels.isNotEmpty() && fetchedFrom == baseUrl) {
            ModelRepository.cacheModels(providerId, fetchedModels)
        }

        // Lets the preference entry refresh the summary it shows; the values are already persisted
        // by this point, which is why the callback runs after the writes rather than before.
        onSaved?.invoke()
        dismiss()
    }

    // --------------------------------------------------------------- helpers

    private fun setFetching(fetching: Boolean) {
        fetchButton?.isEnabled = !fetching
        fetchProgress?.visibility = if (fetching) View.VISIBLE else View.GONE
    }

    private fun hideKeyboard() {
        val view = dialog?.currentFocus ?: return
        val manager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager
        manager?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
