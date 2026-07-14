package com.system.launcher.tools.ui.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.system.launcher.tools.R
import com.system.launcher.tools.data.model.AppEntrySource
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.model.IconStatus
import com.system.launcher.tools.data.model.InstallVerification
import com.system.launcher.tools.data.model.LaunchVerification
import com.system.launcher.tools.data.repository.ProfileAppPolicyStore
import com.system.launcher.tools.databinding.ItemAppManagementBinding
import com.system.launcher.tools.ui.common.SpaceUi

enum class AppManagementMode {
    ALL_APPS,
    ISSUES
}

class AppManagementAdapter(
    private val onAppClick: (AppInfo) -> Unit
) : ListAdapter<AppInfo, AppManagementAdapter.ViewHolder>(DiffCallback()) {

    var mode: AppManagementMode = AppManagementMode.ALL_APPS
        private set

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemAppManagementBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun setMode(newMode: AppManagementMode) {
        if (mode == newMode) return
        mode = newMode
        notifyDataSetChanged()
    }

    fun submitApps(apps: List<AppInfo>) {
        submitList(apps)
    }

    inner class ViewHolder(private val binding: ItemAppManagementBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            SpaceUi.attachPressScale(binding.root, 0.985f)
        }

        fun bind(app: AppInfo) {
            binding.apply {
                val fallback = ContextCompat.getDrawable(root.context, android.R.drawable.sym_def_app_icon)
                ivAppIcon.setImageDrawable(normalizeIcon(app.icon ?: fallback))
                tvAppName.text = app.appName
                tvPackageName.text = app.packageName

                val policy = ProfileAppPolicyStore.resolvePolicy(root.context, app.packageName)
                tvStatus.text = statusText(app, policy)
                tvBadge.text = badgeText(app)

                ivTrailing.setImageResource(R.drawable.ic_chevron_right_24)
                ivTrailing.contentDescription = "进入详情"
                ivTrailing.setOnTouchListener(null)
                root.setOnClickListener { onAppClick(app) }
            }
        }

        private fun badgeText(app: AppInfo): String {
            if (hasIssue(app)) return when (app.installVerification) {
                InstallVerification.CONFIRMED_MISSING -> "已卸载"
                InstallVerification.UNKNOWN -> "待验证"
                else -> "需处理"
            }
            return if (app.showOnHome) "首页" else "未显示"
        }

        private fun statusText(app: AppInfo, policy: ProfileAppPolicyStore.EffectivePolicy): String {
            val parts = mutableListOf<String>()
            if (app.showOnHome) parts += "首页显示" else parts += "未显示"
            if (policy.shouldNeverAutoHide) parts += "系统保活"
            if (!policy.shouldNeverAutoHide && policy.effectiveUserKeepAlive) parts += "后台运行"
            if (app.isSystemCandidate) parts += "系统候选"
            when (app.installVerification) {
                InstallVerification.CONFIRMED_INSTALLED -> parts += "已安装"
                InstallVerification.CONFIRMED_MISSING -> parts += "已卸载"
                InstallVerification.UNKNOWN -> parts += "安装待验证"
            }
            when (app.launchVerification) {
                LaunchVerification.LAUNCHABLE -> parts += "可启动"
                LaunchVerification.POLICY_LAUNCH_ONLY -> parts += "策略启动"
                LaunchVerification.NOT_LAUNCHABLE -> parts += "不可启动"
                LaunchVerification.UNKNOWN -> Unit
            }
            if (app.iconStatus != IconStatus.OK) parts += "图标需修复"
            if (mode == AppManagementMode.ISSUES && app.diagnosticReason.isNotBlank()) parts += app.diagnosticReason
            return parts.distinct().joinToString(" · ")
        }

        private fun normalizeIcon(drawable: Drawable?): Drawable? {
            drawable ?: return null
            val size = (44f * binding.root.resources.displayMetrics.density).toInt().coerceAtLeast(1)
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

    companion object {
        fun hasIssue(app: AppInfo): Boolean {
            return app.installVerification != InstallVerification.CONFIRMED_INSTALLED ||
                app.launchVerification == LaunchVerification.NOT_LAUNCHABLE ||
                app.iconStatus != IconStatus.OK ||
                app.diagnosticReason.isNotBlank()
        }
    }
}
