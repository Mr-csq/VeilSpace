package com.system.launcher.tools.ui.home

import android.R
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.system.launcher.tools.data.model.AppEntrySource
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.model.IconStatus
import com.system.launcher.tools.data.model.InstallVerification
import com.system.launcher.tools.data.model.LaunchVerification
import com.system.launcher.tools.databinding.ItemAppGridBinding
import com.system.launcher.tools.ui.common.SpaceUi
import kotlin.math.abs
import kotlin.math.min

/**
 * 应用网格适配器
 */
class AppGridAdapter(
    private val onAppClick: (AppInfo) -> Unit,
    private val canOpenQuickSettings: (AppInfo) -> Boolean,
    private val onOpenQuickSettings: (AppInfo) -> Unit,
    private val onStartDrag: (ViewHolder) -> Boolean
) : RecyclerView.Adapter<AppGridAdapter.ViewHolder>() {
    private val apps = mutableListOf<AppInfo>()

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount(): Int = apps.size

    override fun getItemId(position: Int): Long = apps[position].packageName.stableId()

    override fun onViewRecycled(holder: ViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    fun submitApps(newApps: List<AppInfo>) {
        val oldApps = apps.toList()
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldApps.size
            override fun getNewListSize(): Int = newApps.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldApps[oldItemPosition].packageName == newApps[newItemPosition].packageName
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldApps[oldItemPosition].contentKey() == newApps[newItemPosition].contentKey()
            }
        })
        apps.clear()
        apps.addAll(newApps)
        diffResult.dispatchUpdatesTo(this)
    }

    fun moveItem(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition !in apps.indices || toPosition !in apps.indices) return false
        val moved = apps.removeAt(fromPosition)
        apps.add(toPosition, moved)
        notifyItemMoved(fromPosition, toPosition)
        notifyItemRangeChanged(min(fromPosition, toPosition), abs(fromPosition - toPosition) + 1)
        return true
    }

    fun finishDragAndGetPackageOrder(): List<String> = apps.map { it.packageName }

    inner class ViewHolder(private val binding: ItemAppGridBinding) : RecyclerView.ViewHolder(binding.root) {
        private val touchSlop = ViewConfiguration.get(binding.root.context).scaledTouchSlop.toFloat()
        private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
        private var boundApp: AppInfo? = null
        private var downX = 0f
        private var downY = 0f
        private var movedBeyondSlop = false
        private var longPressTriggered = false
        private var dragStarted = false
        private val longPressRunnable = Runnable {
            if (boundApp == null || movedBeyondSlop) return@Runnable
            longPressTriggered = true
            SpaceUi.haptic(binding.root)
            binding.root.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120L).start()
        }

        init {
            binding.root.setOnTouchListener { _, event -> handleTouch(event) }
            binding.root.setOnLongClickListener {
                val app = boundApp ?: return@setOnLongClickListener false
                if (!canOpenQuickSettings(app)) return@setOnLongClickListener false
                SpaceUi.haptic(binding.root)
                onOpenQuickSettings(app)
                true
            }
        }

        fun bind(app: AppInfo) {
            resetGesture()
            boundApp = app
            binding.apply {
                val displayName = app.homeDisplayName()
                tvAppName.text = displayName
                root.contentDescription = if (canOpenQuickSettings(app)) {
                    "$displayName，长按打开快捷设置或移动排序"
                } else {
                    "$displayName，长按移动排序"
                }
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

                SpaceUi.setSafeClickListener(root) { onAppClick(app) }
            }
        }

        fun recycle() {
            resetGesture()
            boundApp = null
        }

        private fun handleTouch(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resetGesture()
                    downX = event.x
                    downY = event.y
                    binding.root.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90L).start()
                    binding.root.postDelayed(longPressRunnable, longPressTimeoutMs)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!movedBeyondSlop && dx * dx + dy * dy > touchSlop * touchSlop) {
                        movedBeyondSlop = true
                        binding.root.removeCallbacks(longPressRunnable)
                        if (longPressTriggered && !dragStarted) {
                            dragStarted = onStartDrag(this)
                        }
                        if (!dragStarted) {
                            restoreScale()
                        }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    binding.root.removeCallbacks(longPressRunnable)
                    val app = boundApp
                    when {
                        longPressTriggered && !dragStarted && !movedBeyondSlop && app != null && canOpenQuickSettings(app) -> {
                            restoreScale()
                            onOpenQuickSettings(app)
                        }

                        !longPressTriggered && !movedBeyondSlop -> {
                            restoreScale()
                            binding.root.performClick()
                        }

                        !dragStarted -> restoreScale()
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    binding.root.removeCallbacks(longPressRunnable)
                    if (!dragStarted) restoreScale()
                }
            }
            return true
        }

        private fun resetGesture() {
            binding.root.removeCallbacks(longPressRunnable)
            movedBeyondSlop = false
            longPressTriggered = false
            dragStarted = false
        }

        private fun restoreScale() {
            binding.root.animate().scaleX(1f).scaleY(1f).setDuration(220L).start()
        }

        private fun AppInfo.homeDisplayName(): String {
            return when (packageName) {
                GOOGLE_PLAY_STORE_PACKAGE -> "Google商店"
                GOOGLE_PLAY_SERVICES_PACKAGE -> "Google服务"
                else -> appName
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

    private fun String.stableId(): Long {
        var hash = 1125899906842597L
        for (char in this) hash = 31L * hash + char.code
        return hash and Long.MAX_VALUE
    }

    private fun AppInfo.contentKey(): AppContentKey {
        return AppContentKey(
            packageName = packageName,
            appName = appName,
            isSystemApp = isSystemApp,
            showOnHome = showOnHome,
            keepAlive = keepAlive,
            entrySource = entrySource,
            installVerification = installVerification,
            launchVerification = launchVerification,
            iconStatus = iconStatus,
            diagnosticReason = diagnosticReason
        )
    }

    private data class AppContentKey(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean,
        val showOnHome: Boolean,
        val keepAlive: Boolean,
        val entrySource: AppEntrySource,
        val installVerification: InstallVerification,
        val launchVerification: LaunchVerification,
        val iconStatus: IconStatus,
        val diagnosticReason: String
    )

    private companion object {
        const val GOOGLE_PLAY_STORE_PACKAGE = "com.android.vending"
        const val GOOGLE_PLAY_SERVICES_PACKAGE = "com.google.android.gms"
    }
}
