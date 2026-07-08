package com.system.launcher.tools.data.model

import android.graphics.drawable.Drawable

/**
 * 应用信息数据类
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean = false,
    val showOnHome: Boolean = true,
    val sortOrder: Int = Int.MAX_VALUE,
    val keepAlive: Boolean = false,
    val installed: Boolean = true,
    val launchable: Boolean = true,
    val iconStatus: IconStatus = if (icon == null) IconStatus.MISSING else IconStatus.OK,
    val lastSeenAt: Long = System.currentTimeMillis(),
    val diagnosticReason: String = ""
)

enum class IconStatus {
    OK,
    MISSING,
    STALE
}
