package com.system.launcher.tools.ui.files

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.system.launcher.tools.R
import com.system.launcher.tools.databinding.ItemFileRowBinding
import java.io.File

class FileListAdapter(
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Boolean
) : ListAdapter<FileItem, FileListAdapter.ViewHolder>(DiffCallback()) {

    private val selectedPaths = linkedSetOf<String>()
    private var selectionMode = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun setSelectionState(paths: Set<String>, enabled: Boolean) {
        selectedPaths.clear()
        selectedPaths.addAll(paths)
        selectionMode = enabled
        notifyDataSetChanged()
    }

    inner class ViewHolder(private val binding: ItemFileRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FileItem) {
            val selected = selectedPaths.contains(item.path)
            binding.tvFileName.text = item.name
            binding.tvFileMeta.text = buildMeta(item)
            binding.tvDuration.visibility = if (item.type == FileType.VIDEO) View.VISIBLE else View.GONE
            binding.tvDuration.text = FileFormatters.formatDuration(item.durationMs)
            binding.root.isSelected = selected
            binding.selectionOverlay.visibility = if (selected) View.VISIBLE else View.GONE
            binding.checkContainer.visibility = if (selectionMode) View.VISIBLE else View.GONE
            binding.checkContainer.alpha = if (selected) 1f else 0.32f

            val imageSource = when {
                item.type == FileType.IMAGE -> File(item.path)
                item.type == FileType.VIDEO && item.thumbnailPath != null -> File(item.thumbnailPath)
                else -> null
            }
            if (imageSource != null) {
                binding.ivFileIcon.load(imageSource) {
                    crossfade(true)
                    placeholder(defaultIcon(item))
                    error(defaultIcon(item))
                }
            } else {
                binding.ivFileIcon.setImageResource(defaultIcon(item))
            }

            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { onLongClick(item) }
        }

        private fun buildMeta(item: FileItem): String {
            val typeLabel = when (item.type) {
                FileType.IMAGE -> "图片"
                FileType.VIDEO -> "视频"
                FileType.OTHER -> "文件"
            }
            return listOf(
                typeLabel,
                FileFormatters.formatSize(item.sizeBytes),
                FileFormatters.formatModifiedAt(item.modifiedAt)
            ).filter { it.isNotBlank() }.joinToString(" · ")
        }

        private fun defaultIcon(item: FileItem): Int {
            return when (item.type) {
                FileType.IMAGE -> android.R.drawable.ic_menu_gallery
                FileType.VIDEO -> R.drawable.ic_video_file
                FileType.OTHER -> R.drawable.ic_file_generic
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean = oldItem.path == newItem.path
        override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean = oldItem == newItem
    }
}
