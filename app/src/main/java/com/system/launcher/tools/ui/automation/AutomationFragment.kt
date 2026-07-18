package com.system.launcher.tools.ui.automation

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.system.launcher.tools.automation.AutomationBoundaryType
import com.system.launcher.tools.automation.AutomationConfig
import com.system.launcher.tools.automation.AutomationDateMode
import com.system.launcher.tools.automation.AutomationExecutionResult
import com.system.launcher.tools.automation.AutomationOperationStatus
import com.system.launcher.tools.databinding.FragmentAutomationBinding
import com.system.launcher.tools.databinding.RowAutomationAppBinding
import com.system.launcher.tools.ui.common.SpaceUi
import com.system.launcher.tools.ui.common.showSpaceMessage
import dagger.hilt.android.AndroidEntryPoint
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@AndroidEntryPoint
class AutomationFragment : Fragment() {
    private var _binding: FragmentAutomationBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AutomationViewModel by viewModels()
    private var draft = AutomationConfig()
    private var initialRenderComplete = false
    private var detailsExpanded = false
    private var workdayDataWarning: String? = null
    private val selectedPackages = linkedSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAutomationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupActions()
        observeState()
        SpaceUi.applySystemBarInsets(binding.pageContent)
        SpaceUi.reveal(binding.pageContent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh(recoverMissedBoundary = true)
    }

    private fun setupActions() {
        SpaceUi.setSafeClickListener(binding.btnBack) { findNavController().popBackStack() }
        SpaceUi.setSafeClickListener(binding.btnStartTime) { pickTime(draft.startMinuteOfDay, true) }
        SpaceUi.setSafeClickListener(binding.btnEndTime) { pickTime(draft.endMinuteOfDay, false) }
        binding.radioChinaWorkday.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                draft = draft.copy(dateMode = AutomationDateMode.CHINA_LEGAL_WORKDAY)
                renderDateMode()
            }
        }
        binding.radioCustomWeekdays.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                draft = draft.copy(dateMode = AutomationDateMode.CUSTOM_WEEKDAYS)
                renderDateMode()
            }
        }
        weekdayButtons().forEach { (day, button) ->
            button.setOnCheckedChangeListener { _, _ ->
                if (initialRenderComplete) draft = draft.copy(customWeekdays = selectedWeekdays())
            }
        }
        SpaceUi.setSafeClickListener(binding.btnExactPermission) {
            val intent = viewModel.createExactAlarmPermissionIntent()
            if (intent == null) {
                showSpaceMessage("精确闹钟权限已可用")
            } else {
                runCatching { startActivity(intent) }
                    .onFailure { showSpaceMessage("系统未提供精确闹钟授权页面") }
            }
        }
        SpaceUi.setSafeClickListener(binding.btnToggleDetails) {
            detailsExpanded = !detailsExpanded
            renderDetailsVisibility()
        }
        SpaceUi.setSafeClickListener(binding.btnSave) { saveDraft() }
        SpaceUi.attachPressScale(binding.btnBack, 0.9f)
        SpaceUi.attachPressScale(binding.btnStartTime, 0.98f)
        SpaceUi.attachPressScale(binding.btnEndTime, 0.98f)
        SpaceUi.attachPressScale(binding.btnToggleDetails, 0.98f)
        SpaceUi.attachPressScale(binding.btnSave, 0.98f)
    }

    private fun observeState() {
        viewModel.state.observe(viewLifecycleOwner) { state ->
            draft = state.automation.config
            workdayDataWarning = state.automation.workdayDataWarning
            selectedPackages.clear()
            val visiblePackages = state.apps.mapTo(hashSetOf()) { it.packageName }
            selectedPackages += draft.selectedPackages.filter { it in visiblePackages }
            renderConfig()
            renderApps(state.apps)
            renderRuntimeState(state)
            initialRenderComplete = true
        }
        viewModel.saving.observe(viewLifecycleOwner) { saving ->
            binding.btnSave.isEnabled = !saving
            binding.btnSave.text = if (saving) "正在保存" else "保存并重新安排"
        }
    }

    private fun renderConfig() {
        initialRenderComplete = false
        binding.switchEnabled.isChecked = draft.enabled
        binding.btnStartTime.text = formatMinute(draft.startMinuteOfDay)
        binding.btnEndTime.text = formatMinute(draft.endMinuteOfDay)
        binding.tvTimeHint.text = if (draft.endMinuteOfDay < draft.startMinuteOfDay) {
            "跨午夜：结束边界在所选工作日的次日执行"
        } else {
            "开始和结束只在边界时刻执行，不持续覆盖手动修改"
        }
        binding.radioChinaWorkday.isChecked = draft.dateMode == AutomationDateMode.CHINA_LEGAL_WORKDAY
        binding.radioCustomWeekdays.isChecked = draft.dateMode == AutomationDateMode.CUSTOM_WEEKDAYS
        weekdayButtons().forEach { (day, button) -> button.isChecked = day in draft.customWeekdays }
        renderDateMode()
    }

    private fun renderDateMode() {
        binding.customWeekdaysContainer.visibility = if (draft.dateMode == AutomationDateMode.CUSTOM_WEEKDAYS) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val warning = workdayDataWarning.takeIf {
            draft.dateMode == AutomationDateMode.CHINA_LEGAL_WORKDAY
        }
        binding.tvWorkdayWarning.text = warning.orEmpty()
        binding.tvWorkdayWarning.visibility = if (warning == null) View.GONE else View.VISIBLE
    }

    private fun renderApps(apps: List<AutomationAppChoice>) {
        binding.appsContainer.removeAllViews()
        binding.tvAppsEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        apps.forEachIndexed { index, choice ->
            val row = RowAutomationAppBinding.inflate(layoutInflater, binding.appsContainer, false)
            val selected = choice.packageName in selectedPackages
            row.appName.text = choice.appName
            row.appSummary.text = choice.unavailableReason?.let { choice.packageName + " · " + it }
                ?: choice.packageName
            row.appIcon.setImageDrawable(
                choice.icon ?: requireContext().getDrawable(com.system.launcher.tools.R.drawable.ic_app_icon)
            )
            row.appSwitch.isChecked = selected
            row.appSwitch.isEnabled = choice.eligible || selected
            row.rowContent.alpha = if (choice.eligible || selected) 1f else 0.58f
            row.divider.visibility = if (index == apps.lastIndex) View.GONE else View.VISIBLE

            row.appSwitch.setOnCheckedChangeListener { button, checked ->
                if (checked) {
                    selectedPackages += choice.packageName
                } else {
                    selectedPackages -= choice.packageName
                    if (!choice.eligible) button.isEnabled = false
                }
                updateSelectedSummary()
            }
            SpaceUi.setSafeClickListener(row.rowContent) {
                if (row.appSwitch.isEnabled) {
                    row.appSwitch.isChecked = !row.appSwitch.isChecked
                } else {
                    showSpaceMessage(choice.unavailableReason ?: "该应用不支持工作模式")
                }
            }
            binding.appsContainer.addView(row.root)
        }
        updateSelectedSummary()
    }
    private fun renderRuntimeState(state: AutomationScreenState) {
        val automation = state.automation
        val latest = automation.scheduleSnapshot.latestBoundary
        binding.tvCurrentState.text = when {
            !automation.config.enabled -> "已关闭"
            latest == null -> "等待可用工作日数据或首个边界"
            latest.type == AutomationBoundaryType.START -> "计划处于允许时段（保存不会回溯执行；手动修改保持到下一边界）"
            else -> "计划处于关闭时段（结束边界不会强行中断前台应用）"
        }
        binding.tvNextBoundary.text = automation.scheduleSnapshot.nextBoundary?.let { boundary ->
            "${if (boundary.type == AutomationBoundaryType.START) "开始" else "结束"} · ${formatInstant(boundary.scheduledAt)}"
        } ?: automation.alarmStatus.message
        binding.tvExactStatus.text = automation.alarmStatus.message
        binding.btnExactPermission.visibility = if (viewModel.createExactAlarmPermissionIntent() == null) View.GONE else View.VISIBLE
        val metadata = automation.workdayMetadata
        binding.tvWorkdayData.text = getString(
            com.system.launcher.tools.R.string.automation_workday_metadata,
            metadata.supportedYears.sorted().joinToString("、"),
            metadata.updatedAt,
            metadata.sourceName,
            metadata.sourceUrl
        )
        binding.tvLastResult.text = formatLastResult(automation.lastResult)
        renderDetailsVisibility()
    }

    private fun renderDetailsVisibility() {
        binding.detailsContainer.visibility = if (detailsExpanded) View.VISIBLE else View.GONE
        binding.btnToggleDetails.text = if (detailsExpanded) "收起运行与权限详情" else "查看运行与权限详情"
    }

    private fun saveDraft() {
        val config = draft.copy(
            enabled = binding.switchEnabled.isChecked,
            customWeekdays = selectedWeekdays(),
            selectedPackages = selectedPackages.toSet()
        )
        viewModel.save(config) { result ->
            if (result.errors.isNotEmpty()) {
                binding.tvValidation.visibility = View.VISIBLE
                binding.tvValidation.text = result.errors.joinToString("\n")
                showSpaceMessage("配置未保存，请检查提示")
            } else {
                binding.tvValidation.visibility = View.GONE
                showSpaceMessage(result.alarmStatus?.message ?: "配置已保存")
            }
        }
    }

    private fun pickTime(currentMinute: Int, isStart: Boolean) {
        TimePickerDialog(
            requireContext(),
            { _, hour, minute ->
                val value = hour * 60 + minute
                draft = if (isStart) draft.copy(startMinuteOfDay = value) else draft.copy(endMinuteOfDay = value)
                binding.btnStartTime.text = formatMinute(draft.startMinuteOfDay)
                binding.btnEndTime.text = formatMinute(draft.endMinuteOfDay)
                binding.tvTimeHint.text = if (draft.endMinuteOfDay < draft.startMinuteOfDay) {
                    "跨午夜：结束边界在所选工作日的次日执行"
                } else {
                    "开始和结束只在边界时刻执行，不持续覆盖手动修改"
                }
            },
            currentMinute / 60,
            currentMinute % 60,
            true
        ).show()
    }

    private fun weekdayButtons(): Map<DayOfWeek, CompoundButton> = linkedMapOf(
        DayOfWeek.MONDAY to binding.checkMonday,
        DayOfWeek.TUESDAY to binding.checkTuesday,
        DayOfWeek.WEDNESDAY to binding.checkWednesday,
        DayOfWeek.THURSDAY to binding.checkThursday,
        DayOfWeek.FRIDAY to binding.checkFriday,
        DayOfWeek.SATURDAY to binding.checkSaturday,
        DayOfWeek.SUNDAY to binding.checkSunday
    )

    private fun selectedWeekdays(): Set<DayOfWeek> {
        return weekdayButtons().filterValues { it.isChecked }.keys
    }

    private fun updateSelectedSummary() {
        binding.tvSelectedSummary.text = getString(
            com.system.launcher.tools.R.string.automation_selected_count,
            selectedPackages.size
        )
    }

    private fun formatLastResult(result: AutomationExecutionResult?): String {
        if (result == null) return "尚无执行记录"
        val failures = result.appResults.count { app ->
            app.keepAliveStatus in FAILURE_STATUSES || app.notificationStatus in FAILURE_STATUSES
        }
        val header = buildString {
            append(if (result.boundaryType == AutomationBoundaryType.START) "开始边界" else "结束边界")
            append(" · ").append(formatInstant(result.executedAt))
            append(" · ").append(if (result.completed) "已完成" else "等待工作资料恢复后补偿")
            append(" · ").append(result.appResults.size).append(" 个应用")
            if (failures > 0) append("，").append(failures).append(" 个有失败/降级")
        }
        val details = result.appResults.joinToString("\n") { app ->
            "${app.packageName}: 后台=${statusLabel(app.keepAliveStatus)}，通知=${statusLabel(app.notificationStatus)}" +
                app.detail.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
        }
        return if (details.isBlank()) header else "$header\n$details"
    }

    private fun statusLabel(status: AutomationOperationStatus): String = when (status) {
        AutomationOperationStatus.APPLIED -> "成功"
        AutomationOperationStatus.DEFERRED_UNTIL_BACKGROUND -> "退出后隐藏"
        AutomationOperationStatus.POLICY_NOT_ALLOWED -> "策略不允许"
        AutomationOperationStatus.NOT_INSTALLED -> "未安装"
        AutomationOperationStatus.NO_PROFILE_OWNER -> "无 Profile Owner"
        AutomationOperationStatus.NOT_DECLARED_BY_APP -> "应用未声明"
        AutomationOperationStatus.FAILED -> "失败"
    }

    private fun formatMinute(minute: Int): String = String.format(Locale.CHINA, "%02d:%02d", minute / 60, minute % 60)

    private fun formatInstant(instant: Instant): String {
        return DATE_TIME_FORMATTER.format(instant.atZone(ZoneId.systemDefault()))
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd E HH:mm", Locale.CHINA)
        private val FAILURE_STATUSES = setOf(
            AutomationOperationStatus.FAILED,
            AutomationOperationStatus.NO_PROFILE_OWNER,
            AutomationOperationStatus.POLICY_NOT_ALLOWED,
            AutomationOperationStatus.NOT_INSTALLED
        )
    }
}
