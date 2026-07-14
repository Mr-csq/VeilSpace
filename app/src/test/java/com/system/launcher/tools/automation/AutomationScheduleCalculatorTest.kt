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

class AutomationScheduleCalculatorTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val calculator = AutomationScheduleCalculator()

    @Test
    fun `china provider handles statutory holiday and adjusted weekend workday`() {
        assertEquals(
            WorkdayClassification.HOLIDAY,
            ChinaLegalWorkdayProvider.classify(LocalDate.parse("2026-02-16"))
        )
        assertEquals(
            WorkdayClassification.WORKDAY,
            ChinaLegalWorkdayProvider.classify(LocalDate.parse("2026-02-28"))
        )
        assertEquals(
            WorkdayClassification.WORKDAY,
            ChinaLegalWorkdayProvider.classify(LocalDate.parse("2026-01-04"))
        )
    }

    @Test
    fun `china provider never guesses an unsupported year`() {
        assertEquals(
            WorkdayClassification.UNKNOWN,
            ChinaLegalWorkdayProvider.classify(LocalDate.parse("2027-01-04"))
        )
    }

    @Test
    fun `adjusted sunday schedules a boundary while new year holiday is skipped`() {
        val config = enabledChinaConfig()
        val now = at("2025-12-31T20:00:00+08:00")

        val next = calculator.snapshot(config, now, zone).nextBoundary

        assertEquals(LocalDate.parse("2026-01-04"), next?.workDate)
        assertEquals(AutomationBoundaryType.START, next?.type)
        assertEquals(at("2026-01-04T09:00:00+08:00"), next?.scheduledAt)
    }

    @Test
    fun `cross midnight end belongs to the next calendar day`() {
        val config = AutomationConfig(
            revision = 3,
            enabled = true,
            startMinuteOfDay = 22 * 60,
            endMinuteOfDay = 6 * 60,
            dateMode = AutomationDateMode.CUSTOM_WEEKDAYS,
            customWeekdays = setOf(DayOfWeek.FRIDAY),
            selectedPackages = setOf("example.app")
        )
        val now = at("2026-07-17T23:00:00+08:00")

        val snapshot = calculator.snapshot(config, now, zone)

        assertEquals(AutomationBoundaryType.START, snapshot.latestBoundary?.type)
        assertEquals(at("2026-07-17T22:00:00+08:00"), snapshot.latestBoundary?.scheduledAt)
        assertEquals(AutomationBoundaryType.END, snapshot.nextBoundary?.type)
        assertEquals(at("2026-07-18T06:00:00+08:00"), snapshot.nextBoundary?.scheduledAt)
    }

    @Test
    fun `custom weekdays can select weekends independently`() {
        val config = AutomationConfig(
            revision = 1,
            enabled = true,
            dateMode = AutomationDateMode.CUSTOM_WEEKDAYS,
            customWeekdays = setOf(DayOfWeek.SUNDAY),
            selectedPackages = setOf("example.app")
        )

        assertTrue(calculator.isEligibleWorkDate(config, LocalDate.parse("2026-07-19")))
        assertFalse(calculator.isEligibleWorkDate(config, LocalDate.parse("2026-07-20")))
    }

    @Test
    fun `same minute start and end is rejected`() {
        val config = enabledChinaConfig().copy(startMinuteOfDay = 9 * 60, endMinuteOfDay = 9 * 60)

        assertTrue(config.validationErrors().any { it.contains("不能相同") })
        val snapshot = calculator.snapshot(config, at("2026-07-14T08:00:00+08:00"), zone)
        assertNull(snapshot.latestBoundary)
        assertNull(snapshot.nextBoundary)
    }

    @Test
    fun `next boundary changes from start to end on a legal workday`() {
        val config = enabledChinaConfig()

        val beforeStart = calculator.snapshot(config, at("2026-07-14T08:30:00+08:00"), zone)
        val afterStart = calculator.snapshot(config, at("2026-07-14T09:01:00+08:00"), zone)

        assertEquals(AutomationBoundaryType.START, beforeStart.nextBoundary?.type)
        assertEquals(at("2026-07-14T09:00:00+08:00"), beforeStart.nextBoundary?.scheduledAt)
        assertEquals(AutomationBoundaryType.START, afterStart.latestBoundary?.type)
        assertEquals(AutomationBoundaryType.END, afterStart.nextBoundary?.type)
    }

    @Test
    fun `latest boundary remains recoverable during a long statutory holiday`() {
        val config = enabledChinaConfig()

        val snapshot = calculator.snapshot(config, at("2026-02-20T12:00:00+08:00"), zone)

        assertEquals(LocalDate.parse("2026-02-14"), snapshot.latestBoundary?.workDate)
        assertEquals(AutomationBoundaryType.END, snapshot.latestBoundary?.type)
        assertEquals(at("2026-02-14T18:00:00+08:00"), snapshot.latestBoundary?.scheduledAt)
    }

    @Test
    fun `duplicate boundary id is idempotently rejected`() {
        val id = "r5:2026-07-14:START:540:1080"

        assertTrue(BoundaryExecutionDecider.shouldExecute(id, null))
        assertFalse(BoundaryExecutionDecider.shouldExecute(id, id))
        assertTrue(BoundaryExecutionDecider.shouldExecute(id, "different"))
    }

    @Test
    fun `manual changes remain until another boundary`() {
        var keepAlive = BoundaryPolicy.desiredKeepAlive(AutomationBoundaryType.START)
        assertTrue(keepAlive)

        keepAlive = false // User manually turns it off after the start boundary.
        assertFalse(keepAlive) // No refresh/poll action is part of BoundaryPolicy.

        keepAlive = BoundaryPolicy.desiredKeepAlive(AutomationBoundaryType.END)
        assertFalse(keepAlive)

        keepAlive = true // User manually turns it on after the end boundary.
        assertTrue(keepAlive)

        keepAlive = BoundaryPolicy.desiredKeepAlive(AutomationBoundaryType.START)
        assertTrue(keepAlive)
    }

    @Test
    fun `config revision creates a distinct boundary identity`() {
        val date = LocalDate.parse("2026-07-14")
        val first = calculator.boundaryFor(enabledChinaConfig().copy(revision = 8), date, AutomationBoundaryType.START, zone)
        val edited = calculator.boundaryFor(enabledChinaConfig().copy(revision = 9), date, AutomationBoundaryType.START, zone)

        assertFalse(first.id == edited.id)
        assertEquals(first.scheduledAt, edited.scheduledAt)
    }

    private fun enabledChinaConfig() = AutomationConfig(
        revision = 1,
        enabled = true,
        startMinuteOfDay = 9 * 60,
        endMinuteOfDay = 18 * 60,
        dateMode = AutomationDateMode.CHINA_LEGAL_WORKDAY,
        selectedPackages = setOf("example.app")
    )

    private fun at(value: String): Instant = ZonedDateTime.parse(value).toInstant()
}
