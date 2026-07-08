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
import com.system.launcher.tools.data.model.IconStatus
import com.system.launcher.tools.databinding.ItemAppManagementBinding

class AppManagementAdapter(
    private val onShowChanged: (AppInfo, Boolean) -> Unit,
    private val onKeepAliveChanged: (AppInfo, Boolean) -> Unit,
    private val onMoveUp: (AppInfo) -> Unit,
    private val onMoveDown: (AppInfo) -> Unit,
    private val onManage: (AppInfo) -> Unit
) : ListAdapter<AppInfo, AppManagementAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemAppManagementBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = getItem(position)
        val homeApps = currentList.filter { it.showOnHome }
        val homeIndex = homeApps.indexOfFirst { it.packageName == app.packageName }
        holder.bind(app, homeIndex, homeApps.size)
    }

    inner class ViewHolder(private val binding: ItemAppManagementBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(app: AppInfo, homeIndex: Int, homeCount: Int) {
            binding.apply {
                val fallback = ContextCompat.getDrawable(root.context, R.drawable.sym_def_app_icon)
                ivAppIcon.setImageDrawable(normalizeIcon(app.icon ?: fallback))
                tvAppName.text = app.appName
                tvPackageName.text = app.packageName
                tvStatus.text = statusText(app)
                tvStatus.visibility = if (tvStatus.text.isNullOrBlank()) View.GONE else View.VISIBLE

                switchShow.setOnCheckedChangeListener(null)
                switchShow.isChecked = app.showOnHome
                switchShow.setOnCheckedChangeListener { _, isChecked -> onShowChanged(app, isChecked) }

                switchKeepAlive.setOnCheckedChangeListener(null)
                switchKeepAlive.isChecked = app.keepAlive
                switchKeepAlive.setOnCheckedChangeListener { _, isChecked -> onKeepAliveChanged(app, isChecked) }

                btnMoveUp.isEnabled = app.showOnHome && homeIndex > 0
                btnMoveDown.isEnabled = app.showOnHome && homeIndex >= 0 && homeIndex < homeCount - 1
                btnMoveUp.setOnClickListener { onMoveUp(app) }
                btnMoveDown.setOnClickListener { onMoveDown(app) }
                btnManage.setOnClickListener { onManage(app) }
            }
        }

        private fun statusText(app: AppInfo): String {
            val parts = mutableListOf<String>()
            if (!app.installed) parts += "未安装"
            if (app.installed && !app.launchable) parts += "不可启动"
            if (app.iconStatus != IconStatus.OK) parts += "图标缺失"
            if (app.diagnosticReason.isNotBlank()) parts += app.diagnosticReason
            return parts.distinct().joinToString(" · ")
        }

        private fun normalizeIcon(drawable: Drawable?): Drawable? {
            drawable ?: return null
            val size = (48f * binding.root.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val oldBounds = drawable.bounds
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            drawable.setBounds(oldBounds)
            return BitmapDrawable(binding.root.resources, bitmap)
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean = oldItem.packageName == newItem.packageName
        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean = oldItem == newItem
    }
}

