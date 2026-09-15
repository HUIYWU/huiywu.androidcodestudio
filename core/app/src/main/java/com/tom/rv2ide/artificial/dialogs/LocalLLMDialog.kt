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
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.catalog.LocalLlmSettings
import com.tom.rv2ide.artificial.catalog.ModelRepository
import com.tom.rv2ide.preferences.internal.prefManager
import kotlinx.coroutines.launch

/**
 * Configures the Local LLM provider from the AI Agent preferences page.
 *
 * The model field is a dropdown, but its contents are not hard-coded: local servers decide which
 * models they serve, so the list is fetched from the server's own `/v1/models` endpoint on demand.
 * Until then the stored value is shown, so the dialog is usable without a running server.
 */
class LocalLLMDialog : DialogFragment() {

    private var baseUrlInput: TextInputEditText? = null
    private var apiKeyInput: TextInputEditText? = null
    private var modelDropdown: MaterialAutoCompleteTextView? = null
    private var fetchButton: MaterialButton? = null
    private var fetchProgress: LinearProgressIndicator? = null

    /** Models reported by the server during this dialog's lifetime. */
    private var fetchedModels: List<String> = emptyList()

    /**
     * Invoked after the settings have been written.
     *
     * Held as a settable property rather than a constructor argument so the dialog stays
     * instantiable by the framework, and only survives as long as the dialog it belongs to.
     */
    var onSaved: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_local_llm_prefs, null)

        baseUrlInput = view.findViewById(R.id.baseUrlInput)
        apiKeyInput = view.findViewById(R.id.apiKeyInput)
        modelDropdown = view.findViewById(R.id.modelDropdown)
        fetchButton = view.findViewById(R.id.fetchModelsButton)
        fetchProgress = view.findViewById(R.id.fetchProgress)

        loadSavedConfig(context)

        fetchButton?.setOnClickListener { fetchModels() }

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.local_llm_config_title)
            .setView(view)
            .setPositiveButton(R.string.action_save) { _, _ -> save() }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
    }

    private fun loadSavedConfig(context: Context) {
        baseUrlInput?.setText(
            prefManager.getString(LocalLlmSettings.BASE_URL_KEY, LocalLlmSettings.DEFAULT_BASE_URL)
        )
        apiKeyInput?.setText(prefManager.getString(LocalLlmSettings.API_KEY_KEY, ""))

        // A previously fetched catalogue is preferred over the placeholder name: it holds names the
        // server has actually reported, whereas the placeholder is not a real model. Without one the
        // placeholder stays, since there is no way to guess what a local server serves.
        val stored = prefManager.getString(LocalLlmSettings.MODEL_KEY, null)
        val model = stored
            ?.takeIf { it.isNotBlank() && it != LocalLlmSettings.DEFAULT_MODEL }
            ?: ModelRepository.getModels(LocalLlmSettings.PROVIDER_ID).firstOrNull()
            ?: LocalLlmSettings.DEFAULT_MODEL

        setModelOptions(listOf(model), model)
    }

    /** Replaces the dropdown contents, keeping [selected] as the current value. */
    private fun setModelOptions(models: List<String>, selected: String?) {
        val dropdown = modelDropdown ?: return
        // A non-empty list is required: an empty adapter would clear the field and the saved value
        // would silently look like "no model".
        val options = models.filter { it.isNotBlank() }.ifEmpty {
            listOf(LocalLlmSettings.DEFAULT_MODEL)
        }
        dropdown.setAdapter(
            ArrayAdapter(
                requireContext(),
                com.google.android.material.R.layout.mtrl_auto_complete_simple_item,
                options
            )
        )
        dropdown.setText(
            selected?.takeIf { it.isNotBlank() } ?: options.first(),
            // Without this the TextView is treated as free text and the selection is not committed.
            false
        )
    }

    /**
     * Asks the configured server which models it serves.
     *
     * Unlike the cloud providers this needs the URL the user just typed, so the value is handed to
     * the catalogue instead of being read from preferences — fetching against the previously saved
     * URL would silently list the wrong server's models.
     */
    private fun fetchModels() {
        val context = context ?: return
        val baseUrl = baseUrlInput?.text?.toString()?.trim().orEmpty()
        if (baseUrl.isEmpty()) {
            baseUrlInput?.error = getString(R.string.local_llm_base_url_required)
            return
        }

        val apiKey = apiKeyInput?.text?.toString()?.trim().orEmpty().takeIf { it.isNotEmpty() }

        // Resolved before the spinner is shown: returning after enabling it would leave the button
        // disabled with a progress bar stuck on screen.
        val host = activity as? FragmentActivity ?: return

        hideKeyboard()
        setFetching(true)

        host.lifecycleScope.launch {
            val result = ModelRepository.fetchLocalModels(baseUrl, apiKey)
            // The dialog may have been dismissed while the request was in flight.
            if (!isAdded) return@launch

            setFetching(false)
            when (result) {
                is ModelRepository.LocalModelsResult.Success -> {
                    fetchedModels = result.models
                    val current = modelDropdown?.text?.toString()?.trim()
                    val selected = current?.takeIf { it in result.models } ?: result.models.first()
                    setModelOptions(result.models, selected)
                    toast(context.getString(R.string.local_llm_fetch_loaded, result.models.size))
                }

                is ModelRepository.LocalModelsResult.Failure -> {
                    // Keep whatever is on screen: a failed refresh must not wipe the model name.
                    toast(context.getString(R.string.local_llm_fetch_failed, result.reason))
                }
            }
        }
    }

    private fun save() {
        val baseUrl = baseUrlInput?.text?.toString()?.trim().orEmpty()
        if (baseUrl.isEmpty()) {
            baseUrlInput?.error = getString(R.string.local_llm_base_url_required)
            return
        }

        // The model field is a dropdown, so the value comes from the text it displays; when nothing
        // was fetched it still holds the stored or default name.
        val model = modelDropdown?.text?.toString()?.trim().orEmpty()
            .ifEmpty { LocalLlmSettings.DEFAULT_MODEL }

        prefManager.putString(LocalLlmSettings.BASE_URL_KEY, baseUrl)
        prefManager.putString(LocalLlmSettings.API_KEY_KEY, apiKeyInput?.text?.toString()?.trim().orEmpty())
        prefManager.putString(LocalLlmSettings.MODEL_KEY, model)

        // Make the refreshed list reusable by the request path and the sidebar picker.
        if (fetchedModels.isNotEmpty()) {
            ModelRepository.cacheLocalModels(fetchedModels)
        }

        // Lets the preference entry refresh the summary it shows; the values are already persisted
        // by this point, which is why the callback runs after the writes rather than before.
        onSaved?.invoke()
        dismiss()
    }

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

    companion object {
        /** Tag for [show]; also keeps the dialog recoverable across configuration changes. */
        const val TAG = "local_llm_dialog"
    }
}