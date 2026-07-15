package com.system.launcher.tools.ui.files

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.system.launcher.tools.R
import com.system.launcher.tools.databinding.ItemFileDateHeaderBinding
import com.system.launcher.tools.databinding.ItemFileImageBinding
import com.system.launcher.tools.ui.common.SpaceUi
import java.io.File

class ImageGridAdapter(
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Boolean,
    private val onDateSelectAll: (String) -> Unit
) : ListAdapter<FileSectionItem, RecyclerView.ViewHolder>(DiffCallback()) {

    private val selectedPaths = linkedSetOf<String>()
    private var selectionMode = false

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is FileSectionItem.DateHeader -> VIEW_TYPE_DATE_HEADER
            is FileSectionItem.FileRow -> VIEW_TYPE_IMAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_DATE_HEADER -> DateHeaderViewHolder(ItemFileDateHeaderBinding.inflate(inflater, parent, false))
            else -> ImageViewHolder(ItemFileImageBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is FileSectionItem.DateHeader -> (holder as DateHeaderViewHolder).bind(item)
            is FileSectionItem.FileRow -> (holder as ImageViewHolder).bind(item.item)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            when (val item = getItem(position)) {
                is FileSectionItem.DateHeader -> (holder as DateHeaderViewHolder).bindSelection()
                is FileSectionItem.FileRow -> (holder as ImageViewHolder).bindSelection(item.item)
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    fun submitFiles(files: List<FileItem>, onCommitted: () -> Unit = {}) {
        submitList(FileSectionItem.fromFiles(files), onCommitted)
    }

    fun isDateHeader(position: Int): Boolean {
        return currentList.getOrNull(position) is FileSectionItem.DateHeader
    }

    fun setSelectionState(paths: Set<String>, enabled: Boolean) {
        if (selectionMode == enabled && selectedPaths == paths) return
        val selectionModeChanged = selectionMode != enabled
        val changedPaths = (selectedPaths - paths) + (paths - selectedPaths)
        selectedPaths.clear()
        selectedPaths.addAll(paths)
        selectionMode = enabled
        if (selectionModeChanged) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        } else if (changedPaths.isNotEmpty()) {
            currentList.forEachIndexed { index, item ->
                if (item is FileSectionItem.FileRow && item.item.path in changedPaths) {
                    notifyItemChanged(index, PAYLOAD_SELECTION)
                }
            }
        }
    }

    inner class DateHeaderViewHolder(
        private val binding: ItemFileDateHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            SpaceUi.attachPressScale(binding.btnSelectDate, 0.94f)
        }

        fun bind(item: FileSectionItem.DateHeader) {
            binding.tvDateHeader.text = item.label
            bindSelection()
            SpaceUi.setSafeClickListener(binding.btnSelectDate) { onDateSelectAll(item.key) }
        }

        fun bindSelection() {
            binding.btnSelectDate.visibility = if (selectionMode) View.VISIBLE else View.GONE
        }
    }

    inner class ImageViewHolder(private val binding: ItemFileImageBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            SpaceUi.attachPressScale(binding.root, 0.96f)
        }

        fun bind(item: FileItem) {
            val imageSource = when {
                item.type == FileType.IMAGE -> File(item.path)
                item.type == FileType.VIDEO && item.thumbnailPath != null -> File(item.thumbnailPath)
                else -> null
            }
            if (imageSource != null) {
                binding.ivImage.load(imageSource) {
                    crossfade(true)
                    placeholder(defaultIcon(item))
                    error(defaultIcon(item))
                }
            } else {
                binding.ivImage.setImageResource(defaultIcon(item))
            }

            val video = item.type == FileType.VIDEO
            binding.videoBadge.visibility = if (video) View.VISIBLE else View.GONE
            binding.tvVideoDuration.text = if (video) FileFormatters.formatDuration(item.durationMs) else ""

            binding.tvName.text = item.name
            bindSelection(item)
            SpaceUi.setSafeClickListener(binding.root) { onClick(item) }
            binding.root.setOnLongClickListener { onLongClick(item) }
        }

        fun bindSelection(item: FileItem) {
            val selected = selectedPaths.contains(item.path)
            binding.root.isSelected = selected
            binding.selectionOverlay.visibility = if (selected) View.VISIBLE else View.GONE
            binding.checkContainer.visibility = if (selectionMode) View.VISIBLE else View.GONE
            binding.checkContainer.alpha = if (selected) 1f else 0.32f
        }

        private fun defaultIcon(item: FileItem): Int {
            return when (item.type) {
                FileType.IMAGE -> android.R.drawable.ic_menu_gallery
                FileType.VIDEO -> R.drawable.ic_video_file
                FileType.OTHER -> R.drawable.ic_file_generic
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<FileSectionItem>() {
        override fun areItemsTheSame(oldItem: FileSectionItem, newItem: FileSectionItem): Boolean {
            return when {
                oldItem is FileSectionItem.DateHeader && newItem is FileSectionItem.DateHeader -> oldItem.key == newItem.key
                oldItem is FileSectionItem.FileRow && newItem is FileSectionItem.FileRow -> oldItem.item.path == newItem.item.path
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: FileSectionItem, newItem: FileSectionItem): Boolean = oldItem == newItem
    }

    private companion object {
        const val VIEW_TYPE_DATE_HEADER = 0
        const val VIEW_TYPE_IMAGE = 1
        const val PAYLOAD_SELECTION = "selection"
    }
}
