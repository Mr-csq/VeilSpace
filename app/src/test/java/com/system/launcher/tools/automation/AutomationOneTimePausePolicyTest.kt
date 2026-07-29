package com.system.launcher.tools.automation

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationOneTimePausePolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `start boundary is skipped, switch is released, and pause remains active for the paired end`() {
        val pause = AutomationOneTimePause(
            workDate = LocalDate.parse("2026-07-27"),
            requestedAt = at("2026-07-26T22:00:00+08:00")
        )

        val decision = AutomationOneTimePausePolicy.decide(
            pause,
            boundary(LocalDate.parse("2026-07-27"), AutomationBoundaryType.START)
        )

        assertTrue(decision.skipBoundary)
        assertEquals(AutomationOneTimePausePhase.ACTIVE, decision.pauseAfterBoundary?.phase)
        assertFalse(decision.pauseAfterBoundary?.switchArmed ?: true)
        assertEquals(pause.workDate, decision.pauseAfterBoundary?.workDate)
    }

    @Test
    fun `end boundary is skipped and pause is consumed`() {
        val pause = AutomationOneTimePause(
            workDate = LocalDate.parse("2026-07-27"),
            requestedAt = at("2026-07-26T22:00:00+08:00"),
            phase = AutomationOneTimePausePhase.ACTIVE
        )

        val decision = AutomationOneTimePausePolicy.decide(
            pause,
            boundary(LocalDate.parse("2026-07-27"), AutomationBoundaryType.END)
        )

        assertTrue(decision.skipBoundary)
        assertNull(decision.pauseAfterBoundary)
    }

    @Test
    fun `boundary after target clears stale pause without skipping later workday`() {
        val pause = AutomationOneTimePause(
            workDate = LocalDate.parse("2026-07-27"),
            requestedAt = at("2026-07-26T22:00:00+08:00")
        )

        val decision = AutomationOneTimePausePolicy.decide(
            pause,
            boundary(LocalDate.parse("2026-07-28"), AutomationBoundaryType.START)
        )

        assertFalse(decision.skipBoundary)
        assertNull(decision.pauseAfterBoundary)
    }

    @Test
    fun `boundary before target keeps pause armed`() {
        val pause = AutomationOneTimePause(
            workDate = LocalDate.parse("2026-07-28"),
            requestedAt = at("2026-07-26T22:00:00+08:00")
        )

        val decision = AutomationOneTimePausePolicy.decide(
            pause,
            boundary(LocalDate.parse("2026-07-27"), AutomationBoundaryType.END)
        )

        assertFalse(decision.skipBoundary)
        assertEquals(pause, decision.pauseAfterBoundary)
    }

    @Test
    fun `next start boundary is returned even when next boundary is the current end`() {
        val config = AutomationConfig(
            revision = 1,
            enabled = true,
            dateMode = AutomationDateMode.CUSTOM_WEEKDAYS,
            customWeekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
            selectedPackages = setOf("example.app")
        )

        val snapshot = AutomationScheduleCalculator().snapshot(
            config,
            at("2026-07-27T12:00:00+08:00"),
            zone
        )

        assertEquals(AutomationBoundaryType.END, snapshot.nextBoundary?.type)
        assertEquals(LocalDate.parse("2026-07-28"), snapshot.nextStartBoundary?.workDate)
        assertEquals(AutomationBoundaryType.START, snapshot.nextStartBoundary?.type)
    }

    private fun boundary(workDate: LocalDate, type: AutomationBoundaryType): AutomationBoundary {
        return AutomationBoundary(
            id = "test:$workDate:${type.name}",
            revision = 1,
            type = type,
            workDate = workDate,
            scheduledAt = workDate.atStartOfDay(zone).toInstant()
        )
    }

    private fun at(value: String): Instant = ZonedDateTime.parse(value).toInstant()
}
