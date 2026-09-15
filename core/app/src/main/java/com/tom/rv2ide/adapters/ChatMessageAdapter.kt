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

package com.tom.rv2ide.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.chat.ChatBlock
import com.tom.rv2ide.artificial.chat.ChatMessage
import com.tom.rv2ide.artificial.render.AIMarkdownRenderer

/**
 * Renders the AI sidebar transcript.
 *
 * A file-change row is nested inside the answer that produced it (see [AssistantHolder]) and is
 * collapsed by default; tapping the header reveals the detail area.
 */
class ChatMessageAdapter(
    private val onOpenFile: (String) -> Unit
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {

    /**
     * Keys of the file rows the user expanded.
     *
     * Held here rather than in the row view: the answer row is rebound whenever its blocks change
     * (a pending write finishing, for instance), which would otherwise collapse a row the user had
     * just opened.
     */
    private val expandedFileChanges = mutableSetOf<String>()

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ChatMessage.User -> TYPE_USER
        is ChatMessage.Assistant -> TYPE_ASSISTANT
        is ChatMessage.Status -> TYPE_STATUS
        is ChatMessage.Error -> TYPE_ERROR
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
            TYPE_ASSISTANT -> AssistantHolder(
                inflater.inflate(R.layout.item_chat_assistant, parent, false)
            )
            TYPE_STATUS -> StatusHolder(inflater.inflate(R.layout.item_chat_status, parent, false))
            else -> ErrorHolder(inflater.inflate(R.layout.item_chat_error, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val message = getItem(position)) {
            is ChatMessage.User -> (holder as UserHolder).bind(message)
            is ChatMessage.Assistant -> (holder as AssistantHolder).bind(message)
            is ChatMessage.Status -> (holder as StatusHolder).bind(message)
            is ChatMessage.Error -> (holder as ErrorHolder).bind(message)
        }
    }

    class UserHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: MaterialTextView = view.findViewById(R.id.userText)

        fun bind(message: ChatMessage.User) {
            text.text = message.text
        }
    }

    class StatusHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: MaterialTextView = view.findViewById(R.id.statusLabel)
        private val progress: CircularProgressIndicator = view.findViewById(R.id.statusProgress)

        fun bind(message: ChatMessage.Status) {
            label.text = message.text
            progress.visibility = if (message.isBusy) View.VISIBLE else View.GONE
        }
    }

    class ErrorHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: MaterialTextView = view.findViewById(R.id.errorText)

        fun bind(message: ChatMessage.Error) {
            text.text = message.text
        }
    }

    inner class AssistantHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val container: LinearLayout = view.findViewById(R.id.blockContainer)
        private val progress: CircularProgressIndicator = view.findViewById(R.id.answerProgress)

        fun bind(message: ChatMessage.Assistant) {
            container.removeAllViews()

            val inflater = LayoutInflater.from(itemView.context)
            // Shared instance: a per-bind renderer would rebuild Markwon's parser and plugin chain
            // on every scroll.
            val renderer = AIMarkdownRenderer.shared(itemView.context)

            message.blocks.forEach { block ->
                when (block) {
                    is ChatBlock.Text -> {
                        val text = inflater.inflate(
                            R.layout.item_chat_text, container, false
                        ) as MaterialTextView
                        text.text = renderer.render(block.markdown)
                        container.addView(text)
                    }
                    is ChatBlock.FileChange -> {
                        container.addView(inflateFileChange(inflater, block))
                    }
                }
            }

            progress.visibility = if (message.isBusy) View.VISIBLE else View.GONE
        }

        private fun inflateFileChange(
            inflater: LayoutInflater,
            block: ChatBlock.FileChange
        ): View {
            val row = inflater.inflate(R.layout.item_chat_file_change, container, false)

            val header: View = row.findViewById(R.id.fileChangeHeader)
            val indicator: MaterialTextView = row.findViewById(R.id.expandIndicator)
            val icon: ImageView = row.findViewById(R.id.fileStatusIcon)
            val name: MaterialTextView = row.findViewById(R.id.fileName)
            val path: MaterialTextView = row.findViewById(R.id.filePath)
            val summary: MaterialTextView = row.findViewById(R.id.changeSummary)
            val rowProgress: CircularProgressIndicator =
                row.findViewById(R.id.fileChangeProgress)
            val detail: View = row.findViewById(R.id.fileChangeDetail)
            val openButton: MaterialButton = row.findViewById(R.id.openFileBtn)

            name.text = block.fileName
            path.text = block.filePath
            // Diff statistics and the diff itself are filled in by the follow-up change; until then
            // the detail area offers the only truthful action, which is to open the file.
            summary.visibility = View.GONE

            val isExpanded = expandedFileChanges.contains(keyOf(block))
            detail.visibility = if (isExpanded) View.VISIBLE else View.GONE
            indicator.text = if (isExpanded) "▾" else "▸"

            if (block.pending) {
                rowProgress.visibility = View.VISIBLE
                icon.visibility = View.GONE
            } else if (block.success) {
                rowProgress.visibility = View.GONE
                icon.visibility = View.VISIBLE
                icon.setImageResource(android.R.drawable.ic_menu_save)
                icon.setColorFilter(
                    itemView.context.getColor(android.R.color.holo_green_dark)
                )
            } else {
                rowProgress.visibility = View.GONE
                icon.visibility = View.VISIBLE
                icon.setImageResource(android.R.drawable.ic_delete)
                icon.setColorFilter(
                    itemView.context.getColor(android.R.color.holo_red_dark)
                )
            }

            header.setOnClickListener {
                // Nothing to reveal while the write is still in flight.
                if (block.pending) return@setOnClickListener

                val expanding = expandedFileChanges.add(keyOf(block))
                if (!expanding) expandedFileChanges.remove(keyOf(block))
                detail.visibility = if (expanding) View.VISIBLE else View.GONE
                indicator.text = if (expanding) "▾" else "▸"
            }

            openButton.setOnClickListener { onOpenFile(block.filePath) }

            return row
        }
    }

    /** Key that survives rebinds and distinguishes repeated attempts at the same file. */
    private fun keyOf(block: ChatBlock.FileChange): String =
        block.filePath + "|" + block.newContent.hashCode()

    private companion object {
        const val TYPE_USER = 0
        const val TYPE_ASSISTANT = 1
        const val TYPE_STATUS = 2
        const val TYPE_ERROR = 3

        val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
                oldItem == newItem
        }
    }
}