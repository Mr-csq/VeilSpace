package com.system.launcher.tools.ui.files

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.system.launcher.tools.databinding.ItemFileImageBinding
import java.io.File

class ImageGridAdapter(
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Boolean
) : ListAdapter<FileItem, ImageGridAdapter.ViewHolder>(DiffCallback()) {

    private val selectedPaths = linkedSetOf<String>()
    private var selectionMode = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
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

    inner class ViewHolder(private val binding: ItemFileImageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FileItem) {
            val selected = selectedPaths.contains(item.path)
            binding.ivImage.load(File(item.path)) {
                crossfade(true)
                placeholder(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_menu_report_image)
            }
            binding.tvName.text = item.name
            binding.root.isSelected = selected
            binding.selectionOverlay.visibility = if (selected) View.VISIBLE else View.GONE
            binding.checkContainer.visibility = if (selectionMode) View.VISIBLE else View.GONE
            binding.checkContainer.alpha = if (selected) 1f else 0.32f
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { onLongClick(item) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean = oldItem.path == newItem.path
        override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean = oldItem == newItem
    }
}
