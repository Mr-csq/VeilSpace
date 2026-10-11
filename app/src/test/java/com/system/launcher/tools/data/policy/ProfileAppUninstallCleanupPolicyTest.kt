package com.system.launcher.tools.data.policy

import com.system.launcher.tools.data.model.AppEntrySource
import com.system.launcher.tools.data.model.InstallVerification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAppUninstallCleanupPolicyTest {
    @Test
    fun `unknown cached app is removed when current package state confirms it is missing`() {
        assertTrue(
            ProfileAppUninstallCleanupPolicy.shouldRemove(
                entrySource = AppEntrySource.DISCOVERED_INSTALLED,
                currentVerification = InstallVerification.CONFIRMED_MISSING
            )
        )
    }

    @Test
    fun `system candidates are retained when absent`() {
        assertFalse(
            ProfileAppUninstallCleanupPolicy.shouldRemove(
                entrySource = AppEntrySource.SYSTEM_CANDIDATE,
                currentVerification = InstallVerification.CONFIRMED_MISSING
            )
        )
    }

    @Test
    fun `query failure never removes cached app`() {
        assertFalse(
            ProfileAppUninstallCleanupPolicy.shouldRemove(
                entrySource = AppEntrySource.DISCOVERED_INSTALLED,
                currentVerification = InstallVerification.UNKNOWN
            )
        )
    }
}
