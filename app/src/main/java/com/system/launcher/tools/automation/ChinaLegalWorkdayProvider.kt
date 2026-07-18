package com.system.launcher.tools.automation

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Replaceable, offline-first workday source. The built-in table is copied from the
 * annual holiday notices published by the General Office of the State Council.
 * Unsupported years deliberately return UNKNOWN instead of guessing.
 */
object ChinaLegalWorkdayProvider : WorkdayProvider {
    override val metadata = WorkdayDataMetadata(
        supportedYears = setOf(2026),
        updatedAt = LocalDate.of(2025, 11, 4),
        sourceName = "国务院办公厅年度节假日安排",
        sourceUrl = "https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm"
    )

    private val statutoryHolidays: Set<LocalDate> = buildSet {
        addRange("2026-01-01", "2026-01-03")
        addRange("2026-02-15", "2026-02-23")
        addRange("2026-04-04", "2026-04-06")
        addRange("2026-05-01", "2026-05-05")
        addRange("2026-06-19", "2026-06-21")
        addRange("2026-09-25", "2026-09-27")
        addRange("2026-10-01", "2026-10-07")
    }

    private val adjustedWorkdays: Set<LocalDate> = setOf(
        LocalDate.parse("2026-01-04"),
        LocalDate.parse("2026-02-14"),
        LocalDate.parse("2026-02-28"),
        LocalDate.parse("2026-05-09"),
        LocalDate.parse("2026-09-20"),
        LocalDate.parse("2026-10-10")
    )

    override fun classify(date: LocalDate): WorkdayClassification {
        if (date.year !in metadata.supportedYears) return WorkdayClassification.UNKNOWN
        if (date in adjustedWorkdays) return WorkdayClassification.WORKDAY
        if (date in statutoryHolidays) return WorkdayClassification.HOLIDAY
        return if (date.dayOfWeek in DayOfWeek.MONDAY..DayOfWeek.FRIDAY) {
            WorkdayClassification.WORKDAY
        } else {
            WorkdayClassification.HOLIDAY
        }
    }

    private fun MutableSet<LocalDate>.addRange(start: String, end: String) {
        var current = LocalDate.parse(start)
        val last = LocalDate.parse(end)
        while (!current.isAfter(last)) {
            add(current)
            current = current.plusDays(1)
        }
    }
}
