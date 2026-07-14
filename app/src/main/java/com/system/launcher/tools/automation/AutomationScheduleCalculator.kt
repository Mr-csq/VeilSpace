package com.system.launcher.tools.automation

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class AutomationScheduleCalculator(
    private val workdayProvider: WorkdayProvider = ChinaLegalWorkdayProvider
) {
    data class Snapshot(
        val latestBoundary: AutomationBoundary?,
        val nextBoundary: AutomationBoundary?
    )

    fun snapshot(config: AutomationConfig, now: Instant, zoneId: ZoneId): Snapshot {
        if (!config.enabled || config.validationErrors().isNotEmpty()) return Snapshot(null, null)
        val localToday = now.atZone(zoneId).toLocalDate()
        val boundaries = buildList {
            // A long statutory holiday must still be able to recover the latest
            // boundary that happened before the break.
            var date = localToday.minusDays(370)
            val lastDate = localToday.plusDays(370)
            while (!date.isAfter(lastDate)) {
                if (isEligibleWorkDate(config, date)) {
                    add(boundaryFor(config, date, AutomationBoundaryType.START, zoneId))
                    add(boundaryFor(config, date, AutomationBoundaryType.END, zoneId))
                }
                date = date.plusDays(1)
            }
        }.sortedBy { it.scheduledAt }

        return Snapshot(
            latestBoundary = boundaries.lastOrNull { !it.scheduledAt.isAfter(now) },
            nextBoundary = boundaries.firstOrNull { it.scheduledAt.isAfter(now) }
        )
    }

    fun boundaryFor(
        config: AutomationConfig,
        workDate: LocalDate,
        type: AutomationBoundaryType,
        zoneId: ZoneId
    ): AutomationBoundary {
        val minuteOfDay = when (type) {
            AutomationBoundaryType.START -> config.startMinuteOfDay
            AutomationBoundaryType.END -> config.endMinuteOfDay
        }
        val boundaryDate = when {
            type == AutomationBoundaryType.END && config.endMinuteOfDay < config.startMinuteOfDay -> workDate.plusDays(1)
            else -> workDate
        }
        val scheduledAt = boundaryDate
            .atTime(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60))
            .atZone(zoneId)
            .toInstant()
        val id = "r${config.revision}:$workDate:${type.name}:${config.startMinuteOfDay}:${config.endMinuteOfDay}"
        return AutomationBoundary(id, config.revision, type, workDate, scheduledAt)
    }

    fun isEligibleWorkDate(config: AutomationConfig, date: LocalDate): Boolean {
        return when (config.dateMode) {
            AutomationDateMode.CHINA_LEGAL_WORKDAY -> workdayProvider.classify(date) == WorkdayClassification.WORKDAY
            AutomationDateMode.CUSTOM_WEEKDAYS -> date.dayOfWeek in config.customWeekdays
        }
    }

    fun providerMetadata(): WorkdayDataMetadata = workdayProvider.metadata

    fun isOfficialDataAvailableFor(date: LocalDate): Boolean {
        return workdayProvider.classify(date) != WorkdayClassification.UNKNOWN
    }
}
