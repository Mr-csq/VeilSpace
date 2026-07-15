package com.system.launcher.tools.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrossProfileTargetSelectorTest {

    @Test
    fun crossProfileTargetTakesPriorityOverLauncherFallback() {
        val target = CrossProfileTargetSelector.select(
            current = "work",
            crossProfileTargets = listOf("personal"),
            launcherProfiles = listOf("work", "fallback")
        )

        assertEquals("personal", target)
    }

    @Test
    fun launcherProfileIsUsedWhenCrossProfileTargetsAreEmpty() {
        val target = CrossProfileTargetSelector.select(
            current = "work",
            crossProfileTargets = emptyList(),
            launcherProfiles = listOf("work", "personal")
        )

        assertEquals("personal", target)
    }

    @Test
    fun currentProfileIsNeverReturned() {
        assertNull(
            CrossProfileTargetSelector.select(
                current = "work",
                crossProfileTargets = listOf("work"),
                launcherProfiles = listOf("work")
            )
        )
    }
}
