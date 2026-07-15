package com.system.launcher.tools.work

enum class WorkProfileConnectionState {
    CURRENT_PROFILE_OWNER,
    CONNECTED_MANAGED_PROFILE,
    OTHER_PROFILE_PRESENT,
    NO_PROFILE
}

object WorkProfileConnectionDecider {
    fun decide(
        isProfileOwner: Boolean,
        hasCrossProfileTarget: Boolean,
        hasOtherProfile: Boolean
    ): WorkProfileConnectionState {
        return when {
            isProfileOwner -> WorkProfileConnectionState.CURRENT_PROFILE_OWNER
            hasCrossProfileTarget -> WorkProfileConnectionState.CONNECTED_MANAGED_PROFILE
            hasOtherProfile -> WorkProfileConnectionState.OTHER_PROFILE_PRESENT
            else -> WorkProfileConnectionState.NO_PROFILE
        }
    }
}
