package com.system.launcher.tools.data.policy

import com.system.launcher.tools.data.model.AppEntrySource
import com.system.launcher.tools.data.model.InstallVerification

internal object ProfileAppUninstallCleanupPolicy {
    fun shouldRemove(
        entrySource: AppEntrySource,
        currentVerification: InstallVerification
    ): Boolean {
        if (entrySource == AppEntrySource.INTERNAL || entrySource == AppEntrySource.SYSTEM_CANDIDATE) {
            return false
        }
        return currentVerification == InstallVerification.CONFIRMED_MISSING
    }
}
