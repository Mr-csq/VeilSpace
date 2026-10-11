package com.system.launcher.tools.ui.automation

import com.system.launcher.tools.automation.AutomationConfig
import java.time.DayOfWeek

internal object AutomationMasterSwitchSave {
    fun save(
        enabled: Boolean,
        draft: AutomationConfig,
        selectedWeekdays: Set<DayOfWeek>,
        selectedPackages: Set<String>,
        persist: (AutomationConfig) -> Unit
    ) {
        persist(
            draft.copy(
                enabled = enabled,
                customWeekdays = selectedWeekdays,
                selectedPackages = selectedPackages
            )
        )
    }
}
