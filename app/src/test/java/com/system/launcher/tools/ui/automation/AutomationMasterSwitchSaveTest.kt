package com.system.launcher.tools.ui.automation

import com.system.launcher.tools.automation.AutomationConfig
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationMasterSwitchSaveTest {
    @Test
    fun `turning master switch off immediately persists disabled config for next screen entry`() {
        val current = AutomationConfig(
            revision = 4,
            enabled = true,
            startMinuteOfDay = 8 * 60,
            endMinuteOfDay = 17 * 60,
            customWeekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            selectedPackages = setOf("example.app")
        )
        var savedConfig: AutomationConfig? = null

        AutomationMasterSwitchSave.save(
            enabled = false,
            draft = current,
            selectedWeekdays = current.customWeekdays,
            selectedPackages = current.selectedPackages,
            persist = { savedConfig = it }
        )

        assertEquals(false, savedConfig?.enabled)
        assertEquals(current.startMinuteOfDay, savedConfig?.startMinuteOfDay)
        assertEquals(current.endMinuteOfDay, savedConfig?.endMinuteOfDay)
        assertEquals(current.customWeekdays, savedConfig?.customWeekdays)
        assertEquals(current.selectedPackages, savedConfig?.selectedPackages)

        val configOnNextEntry = savedConfig ?: error("Master switch change was not persisted")
        assertFalse(configOnNextEntry.enabled)
        assertTrue(configOnNextEntry.validationErrors().isEmpty())
    }
}
