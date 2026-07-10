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
import com.system.launcher.tools.data.model.AppEntrySource
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.model.IconStatus
import com.system.launcher.tools.data.model.InstallVerification
import com.system.launcher.tools.data.model.LaunchVerification
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
                root.contentDescription = app.appName
                val fallback = ContextCompat.getDrawable(root.context, R.drawable.sym_def_app_icon)
                ivAppIcon.setImageDrawable(normalizeIcon(app.icon ?: fallback))
                val blocked = app.installVerification == InstallVerification.CONFIRMED_MISSING ||
                    app.launchVerification == LaunchVerification.NOT_LAUNCHABLE
                val pending = app.installVerification == InstallVerification.UNKNOWN
                root.alpha = when {
                    blocked -> 0.58f
                    pending -> 0.82f
                    else -> 1f
                }
                val status = when {
                    app.installVerification == InstallVerification.CONFIRMED_MISSING -> "已卸载"
                    app.launchVerification == LaunchVerification.NOT_LAUNCHABLE -> "不可启动"
                    app.isSystemCandidate && app.installVerification == InstallVerification.UNKNOWN -> "候选"
                    app.isCachedButUnverified -> "待验证"
                    else -> ""
                }
                tvAppStatus.visibility = if (status.isNotBlank()) View.VISIBLE else View.GONE
                tvAppStatus.text = status

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
        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean = oldItem.contentKey() == newItem.contentKey()

        private fun AppInfo.contentKey(): AppContentKey {
            return AppContentKey(
                packageName = packageName,
                appName = appName,
                isSystemApp = isSystemApp,
                showOnHome = showOnHome,
                sortOrder = sortOrder,
                keepAlive = keepAlive,
                entrySource = entrySource,
                installVerification = installVerification,
                launchVerification = launchVerification,
                iconStatus = iconStatus,
                diagnosticReason = diagnosticReason
            )
        }
    }

    private data class AppContentKey(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean,
        val showOnHome: Boolean,
        val sortOrder: Int,
        val keepAlive: Boolean,
        val entrySource: AppEntrySource,
        val installVerification: InstallVerification,
        val launchVerification: LaunchVerification,
        val iconStatus: IconStatus,
        val diagnosticReason: String
    )
}
