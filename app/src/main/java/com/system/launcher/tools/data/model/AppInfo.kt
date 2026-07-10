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
    val entrySource: AppEntrySource = AppEntrySource.CACHED,
    val installVerification: InstallVerification = InstallVerification.UNKNOWN,
    val launchVerification: LaunchVerification = LaunchVerification.UNKNOWN,
    val iconStatus: IconStatus = if (icon == null) IconStatus.MISSING else IconStatus.OK,
    val launcherComponentNames: Set<String> = emptySet(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    val diagnosticReason: String = ""
) {
    val installed: Boolean
        get() = installVerification == InstallVerification.CONFIRMED_INSTALLED

    val launchable: Boolean
        get() = launchVerification == LaunchVerification.LAUNCHABLE ||
            launchVerification == LaunchVerification.POLICY_LAUNCH_ONLY

    val canAttemptLaunch: Boolean
        get() = installVerification != InstallVerification.CONFIRMED_MISSING &&
            launchVerification != LaunchVerification.NOT_LAUNCHABLE

    val isCachedButUnverified: Boolean
        get() = entrySource == AppEntrySource.CACHED &&
            installVerification == InstallVerification.UNKNOWN

    val isSystemCandidate: Boolean
        get() = entrySource == AppEntrySource.SYSTEM_CANDIDATE
}

enum class AppEntrySource {
    CACHED,
    DISCOVERED_INSTALLED,
    SYSTEM_CANDIDATE,
    INTERNAL
}

enum class InstallVerification {
    CONFIRMED_INSTALLED,
    CONFIRMED_MISSING,
    UNKNOWN
}

enum class LaunchVerification {
    LAUNCHABLE,
    NOT_LAUNCHABLE,
    POLICY_LAUNCH_ONLY,
    UNKNOWN
}

enum class IconStatus {
    OK,
    MISSING,
    STALE
}
