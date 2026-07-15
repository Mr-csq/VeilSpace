package com.system.launcher.tools.work

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkProfileConnectionDeciderTest {
    @Test
    fun `profile owner always uses current profile`() {
        assertEquals(
            WorkProfileConnectionState.CURRENT_PROFILE_OWNER,
            WorkProfileConnectionDecider.decide(
                isProfileOwner = true,
                hasCrossProfileTarget = true,
                hasOtherProfile = true
            )
        )
    }

    @Test
    fun `cross profile target identifies a connected VeilSpace profile`() {
        assertEquals(
            WorkProfileConnectionState.CONNECTED_MANAGED_PROFILE,
            WorkProfileConnectionDecider.decide(
                isProfileOwner = false,
                hasCrossProfileTarget = true,
                hasOtherProfile = true
            )
        )
    }

    @Test
    fun `unconnected profile is treated as a conflict instead of authorization`() {
        assertEquals(
            WorkProfileConnectionState.OTHER_PROFILE_PRESENT,
            WorkProfileConnectionDecider.decide(
                isProfileOwner = false,
                hasCrossProfileTarget = false,
                hasOtherProfile = true
            )
        )
    }

    @Test
    fun `absence of every capability allows provisioning`() {
        assertEquals(
            WorkProfileConnectionState.NO_PROFILE,
            WorkProfileConnectionDecider.decide(
                isProfileOwner = false,
                hasCrossProfileTarget = false,
                hasOtherProfile = false
            )
        )
    }
}
