package com.system.launcher.tools.ui.home

import android.R
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.databinding.ItemAppGridBinding

/**
 * 应用网格适配器
 */
class AppGridAdapter(
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: (AppInfo) -> Boolean
) : ListAdapter<AppInfo, AppGridAdapter.ViewHolder>(AppDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemAppGridBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(app: AppInfo) {
            binding.apply {
                tvAppName.text = app.appName
                val fallback = ContextCompat.getDrawable(root.context, R.drawable.sym_def_app_icon)
                ivAppIcon.setImageDrawable(normalizeIcon(app.icon ?: fallback))
                val unavailable = !app.installed || !app.launchable
                root.alpha = if (unavailable) 0.58f else 1f
                tvAppStatus.visibility = if (unavailable) View.VISIBLE else View.GONE
                tvAppStatus.text = when {
                    !app.installed -> "未安装"
                    !app.launchable -> "不可启动"
                    else -> ""
                }

                root.setOnClickListener { onAppClick(app) }
                root.setOnLongClickListener { onAppLongClick(app) }
            }
        }

        private fun normalizeIcon(drawable: Drawable?): Drawable? {
            drawable ?: return null
            val size = (64f * binding.root.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val oldBounds = drawable.bounds
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            drawable.setBounds(oldBounds)
            return BitmapDrawable(binding.root.resources, bitmap)
        }
    }

    private class AppDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean = oldItem.packageName == newItem.packageName
        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean = oldItem == newItem
    }
}
