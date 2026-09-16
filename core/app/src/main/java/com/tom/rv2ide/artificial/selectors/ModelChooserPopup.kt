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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.selectors

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.catalog.ModelSources
import kotlin.math.roundToInt

/**
 * Provider/model dropdown anchored to the chat composer's model chip.
 *
 * A [ListPopupWindow] rather than a dialog: the chip is a control inside the composer, and its choices
 * belong next to it. A modal dialog would cover the transcript and, because it needs its own title and
 * buttons, would read as a separate screen for what is a one-tap switch.
 *
 * Deliberately separate from `ProviderConfigDialog`: that one *edits* a provider's credentials and
 * catalogue, whereas this only selects among what is already configured. Routing the chip to a config
 * dialog would demand an API key when the user only meant to switch models.
 *
 * Entries are provider-major (`provider — model`) because a model name on its own does not say which
 * provider it belongs to, and the same name can exist on several. The provider in effect is marked,
 * since the list shows every provider at once.
 *
 * A provider without a valid API key stays visible but is not selectable. Hiding it would make the user
 * wonder where their provider went, and choosing it could only fail: `AIAgentManager.setProvider`
 * refuses to activate a provider whose key is missing. Such a row is dimmed and does not respond to a
 * tap, so the missing key stays the visible problem instead of the tap becoming a silent no-op.
 *
 * Callers must [dismiss] when the anchor's view is destroyed; the popup holds the anchor, which would
 * otherwise outlive it.
 */
class ModelChooserPopup(context: Context) {

    private val context: Context = context

    private var popup: ListPopupWindow? = null

    /**
     * Opens the dropdown below [anchor], or above it when there is no room below.
     *
     * [onPicked] receives the chosen `providerId`; only the provider is reported, because the caller
     * already knows how to read that provider's model from `Agents`, and doing it there keeps this
     * class free of the switching policy.
     */
    fun show(anchor: View, onPicked: (String) -> Unit) {
        dismiss()

        val agents = Agents(context)
        val currentProviderId = agents.getProvider()
        val unknown = context.getString(R.string.chat_model_unknown)
        val providerIds = ModelSources.PROVIDER_IDS

        val entries = providerIds.map { providerId ->
            val label = PROVIDER_LABELS[providerId] ?: providerId
            val model = agents.getModel(providerId)?.takeIf { it.isNotBlank() } ?: unknown
            val mark = if (providerId == currentProviderId) ACTIVE_MARK else ""
            "$mark$label — $model"
        }

        val selectable = providerIds.map { providerId ->
            AIAgentRegistry.getFactory(providerId)?.hasValidApiKey() == true
        }

        val adapter = object : ArrayAdapter<String>(
            context,
            R.layout.item_dropdown_single_line,
            entries
        ) {
            // Disabled rows are dimmed and inert rather than hidden: a tap on one cannot switch
            // anything, so the popup stays open and the row itself explains the problem.
            override fun areAllItemsEnabled(): Boolean = false

            override fun isEnabled(position: Int): Boolean = selectable.getOrElse(position) { false }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also { row ->
                    row.alpha = if (isEnabled(position)) 1f else DISABLED_ALPHA
                }
        }

        val visibleFrame = Rect()
        anchor.getWindowVisibleDisplayFrame(visibleFrame)

        // The composer sits at the very bottom edge, and under edge-to-edge the visible frame is not
        // shrunk for the keyboard - so the IME has to be subtracted explicitly. Without this the room
        // "below" the chip looks plentiful and the list opens straight into the keyboard.
        val ime = ViewCompat.getRootWindowInsets(anchor)
            ?.getInsets(WindowInsetsCompat.Type.ime())
            ?.bottom ?: 0

        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        val anchorTop = anchorLocation[1]
        val anchorBottom = anchorTop + anchor.height

        // Rows are uniform (item_dropdown_single_line.xml is single-line with a 48dp minHeight), so the
        // content height can be derived without measuring - the same approach the ATC wizard's
        // dropdown uses.
        val rowHeight = dp(anchor, ROW_HEIGHT_DP)
        val contentHeight = entries.size * rowHeight + dp(anchor, VERTICAL_PADDING_DP)

        val availableBelow = (visibleFrame.bottom - ime - anchorBottom).coerceAtLeast(0)
        val availableAbove = (anchorTop - visibleFrame.top).coerceAtLeast(0)
        val showAbove = contentHeight > availableBelow && availableAbove > availableBelow
        val resolvedHeight =
            if (showAbove) contentHeight.coerceAtMost(availableAbove)
            else contentHeight.coerceAtMost(availableBelow)

        val window = ListPopupWindow(context).apply {
            anchorView = anchor
            // Modal so a tap outside or Back closes the list, as a dropdown is expected to.
            isModal = true
            setAdapter(adapter)
            setBackgroundDrawable(
                ContextCompat.getDrawable(context, R.drawable.bg_atc_dropdown_popup)
            )
            // Flipping is done by offsetting the whole list above the anchor; the default would grow
            // downwards into the keyboard.
            verticalOffset = if (showAbove) -(anchor.height + resolvedHeight) else 0
            if (resolvedHeight > rowHeight) {
                height = resolvedHeight
            }
            setOnItemClickListener { _, _, position, _ ->
                providerIds.getOrNull(position)?.let(onPicked)
                dismiss()
            }
            setOnDismissListener {
                if (popup === this) popup = null
            }
        }

        popup = window
        window.show()
    }

    /** Closes the dropdown, if it is open. Safe to call repeatedly. */
    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).roundToInt()

    private companion object {
        /** Marks the provider in effect; the list shows all of them at once. */
        const val ACTIVE_MARK = "✓ "

        /** Alpha of a row whose provider has no usable API key; Material's disabled state. */
        const val DISABLED_ALPHA = 0.38f

        /** One row's height; matches the minHeight of item_dropdown_single_line.xml. */
        const val ROW_HEIGHT_DP = 48

        /** Slack for the popup's own vertical padding, added to the measured content height. */
        const val VERTICAL_PADDING_DP = 8

        private val PROVIDER_LABELS = mapOf(
            "gemini" to "Google Gemini",
            "openai" to "OpenAI",
            "claude" to "Anthropic Claude",
            "deepseek" to "DeepSeek",
            "grok" to "xAI Grok",
            "localllm" to "Local LLM"
        )
    }
}
