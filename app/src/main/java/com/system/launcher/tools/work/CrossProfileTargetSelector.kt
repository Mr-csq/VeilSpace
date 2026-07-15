package com.system.launcher.tools.work

internal object CrossProfileTargetSelector {
    fun <T> select(current: T, crossProfileTargets: List<T>, launcherProfiles: List<T>): T? {
        return crossProfileTargets.firstOrNull { it != current }
            ?: launcherProfiles.firstOrNull { it != current }
    }
}
