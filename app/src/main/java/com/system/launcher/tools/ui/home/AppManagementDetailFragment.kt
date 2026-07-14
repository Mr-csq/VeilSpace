package com.system.launcher.tools.ui.home

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.system.launcher.tools.data.model.AppEntrySource
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.model.IconStatus
import com.system.launcher.tools.data.model.InstallVerification
import com.system.launcher.tools.data.model.LaunchVerification
import com.system.launcher.tools.data.repository.ProfileAppPolicyStore
import com.system.launcher.tools.databinding.FragmentAppManagementDetailBinding
import com.system.launcher.tools.ui.common.SpaceUi
import com.system.launcher.tools.ui.common.showSpace
import com.system.launcher.tools.ui.common.showSpaceMessage
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AppManagementDetailFragment : Fragment() {
    private var _binding: FragmentAppManagementDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AppManagementViewModel by viewModels()
    private lateinit var packageName: String
    private var currentApp: AppInfo? = null
    private var pendingUninstallApp: AppInfo? = null

    private val uninstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val app = pendingUninstallApp ?: return@registerForActivityResult
        pendingUninstallApp = null
        viewModel.finalizeUninstall(app) { removed ->
            val message = if (removed) "应用已卸载，记录已标记为未安装" else "卸载未完成，应用仍在隐藏空间中"
            showSpaceMessage(message, error = !removed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageName = requireArguments().getString(AppManagementFragment.ARG_PACKAGE_NAME).orEmpty()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAppManagementDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupActions()
        observeViewModel()
        SpaceUi.reveal(binding.pageContent)
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnRefreshStatus.setOnClickListener {
            showSpaceMessage("正在刷新应用状态")
            viewModel.refreshAll()
        }
        binding.btnRefreshIcon.setOnClickListener {
            val app = currentApp ?: return@setOnClickListener
            viewModel.refreshIcon(app)
            showSpaceMessage("正在刷新图标")
        }
        binding.btnUninstallApp.setOnClickListener {
            currentApp?.let { confirmUninstall(it) }
        }
        binding.btnRemoveRecord.setOnClickListener {
            currentApp?.let { confirmRemoveRecord(it) }
        }
        SpaceUi.attachPressScale(binding.btnBack, 0.9f)
        SpaceUi.attachPressScale(binding.btnRefreshStatus, 0.9f)
        SpaceUi.attachPressScale(binding.btnRefreshIcon, 0.985f)
        SpaceUi.attachPressScale(binding.btnUninstallApp, 0.985f)
        SpaceUi.attachPressScale(binding.btnRemoveRecord, 0.985f)
    }

    private fun observeViewModel() {
        viewModel.apps.observe(viewLifecycleOwner) { apps ->
            val app = apps.firstOrNull { it.packageName == packageName }
            if (app == null) {
                if (apps.isNotEmpty()) {
                    showSpaceMessage("应用记录已不存在", error = true)
                    findNavController().popBackStack()
                }
                return@observe
            }
            currentApp = app
            bindApp(app)
        }
    }

    private fun bindApp(app: AppInfo) {
        val fallback = ContextCompat.getDrawable(requireContext(), android.R.drawable.sym_def_app_icon)
        binding.ivAppIcon.setImageDrawable(app.icon ?: fallback)
        binding.tvAppName.text = app.appName
        binding.tvPackageName.text = app.packageName
        binding.tvStatusSummary.text = statusSummary(app)
        bindShowSwitch(app)
        bindKeepAliveSwitch(app)
        bindStatus(app)
        bindOperations(app)
    }

    private fun bindShowSwitch(app: AppInfo) {
        val canShow = app.installVerification != InstallVerification.CONFIRMED_MISSING
        binding.switchShow.setOnCheckedChangeListener(null)
        binding.switchShow.isChecked = app.showOnHome && canShow
        binding.switchShow.isEnabled = canShow
        binding.switchShow.alpha = if (canShow) 1f else 0.6f
        binding.tvShowHint.text = if (canShow) {
            "关闭后应用仍保留在隐藏空间，只是不出现在首页。"
        } else {
            "应用已卸载，不能显示在首页；可在操作区移除记录。"
        }
        binding.switchShow.setOnCheckedChangeListener { _, isChecked ->
            if (canShow) viewModel.setShowOnHome(app, isChecked)
        }
    }

    private fun bindKeepAliveSwitch(app: AppInfo) {
        val policy = ProfileAppPolicyStore.resolvePolicy(requireContext(), app.packageName)
        val internalApp = viewModel.isInternalFileManagerApp(app)
        val systemProtected = policy.shouldNeverAutoHide
        val keepAliveLocked = internalApp || systemProtected || !policy.staticPolicy.userKeepAliveAllowed ||
            app.installVerification == InstallVerification.CONFIRMED_MISSING

        binding.switchKeepAlive.setOnCheckedChangeListener(null)
        binding.switchKeepAlive.text = when {
            internalApp -> "内部入口"
            systemProtected -> "系统保活"
            !policy.staticPolicy.userKeepAliveAllowed -> "强制自动隐藏"
            else -> "允许后台运行"
        }
        binding.switchKeepAlive.isChecked = systemProtected || policy.effectiveUserKeepAlive ||
            (policy.staticPolicy.userKeepAliveAllowed && app.keepAlive)
        binding.switchKeepAlive.isEnabled = !keepAliveLocked
        binding.switchKeepAlive.alpha = if (keepAliveLocked) 0.6f else 1f
        binding.tvKeepAliveHint.text = when {
            internalApp -> "这是隐藏空间内部入口，不需要配置后台运行。"
            systemProtected -> "系统策略保护的入口会保持可用，不会按普通应用自动隐藏。"
            !policy.staticPolicy.userKeepAliveAllowed -> "该应用受策略限制，离开前台后会自动隐藏。"
            app.installVerification == InstallVerification.CONFIRMED_MISSING -> "应用已卸载，不能配置后台运行。"
            binding.switchKeepAlive.isChecked -> "开启后应用不会被自动隐藏，适合需要后台连接或常驻的应用。"
            else -> "关闭后离开前台会自动隐藏，减少工作资料桌面残留。"
        }
        binding.switchKeepAlive.setOnCheckedChangeListener { _, isChecked ->
            if (!keepAliveLocked) viewModel.setKeepAlive(app, isChecked)
        }
    }

    private fun bindStatus(app: AppInfo) {
        binding.tvStatusDetail.text = buildString {
            appendLine("入口来源：${entrySourceLabel(app.entrySource)}")
            appendLine("安装验证：${installVerificationLabel(app.installVerification)}")
            appendLine("启动验证：${launchVerificationLabel(app.launchVerification)}")
            appendLine("图标状态：${iconStatusLabel(app.iconStatus)}")
            appendLine("隐藏策略：${viewModel.autoHideStatusLabel(app)}")
        }.trimEnd()
        binding.tvDiagnosticReason.text = app.diagnosticReason
        binding.tvDiagnosticReason.visibility = if (app.diagnosticReason.isBlank()) View.GONE else View.VISIBLE
    }

    private fun bindOperations(app: AppInfo) {
        val internalApp = viewModel.isInternalFileManagerApp(app)
        binding.btnRefreshIcon.visibility = if (internalApp) View.GONE else View.VISIBLE
        binding.btnUninstallApp.visibility = if (internalApp) View.GONE else View.VISIBLE
        binding.btnRemoveRecord.visibility = if (internalApp) View.GONE else View.VISIBLE
        binding.btnUninstallApp.isEnabled = app.installVerification != InstallVerification.CONFIRMED_MISSING
    }

    private fun statusSummary(app: AppInfo): String {
        val parts = mutableListOf<String>()
        parts += if (app.showOnHome && app.installVerification != InstallVerification.CONFIRMED_MISSING) "首页显示" else "未在首页"
        when (app.installVerification) {
            InstallVerification.CONFIRMED_INSTALLED -> parts += "已安装"
            InstallVerification.CONFIRMED_MISSING -> parts += "已卸载"
            InstallVerification.UNKNOWN -> parts += "安装待验证"
        }
        when (app.launchVerification) {
            LaunchVerification.LAUNCHABLE -> parts += "可启动"
            LaunchVerification.POLICY_LAUNCH_ONLY -> parts += "策略启动"
            LaunchVerification.NOT_LAUNCHABLE -> parts += "不可启动"
            LaunchVerification.UNKNOWN -> Unit
        }
        if (app.keepAlive) parts += "后台运行"
        if (AppManagementAdapter.hasIssue(app)) parts += "需处理"
        return parts.distinct().joinToString(" · ")
    }

    private fun entrySourceLabel(source: AppEntrySource): String {
        return when (source) {
            AppEntrySource.CACHED -> "缓存记录"
            AppEntrySource.DISCOVERED_INSTALLED -> "当前发现"
            AppEntrySource.SYSTEM_CANDIDATE -> "系统候选"
            AppEntrySource.INTERNAL -> "内部入口"
        }
    }

    private fun installVerificationLabel(verification: InstallVerification): String {
        return when (verification) {
            InstallVerification.CONFIRMED_INSTALLED -> "确认已安装"
            InstallVerification.CONFIRMED_MISSING -> "确认已卸载"
            InstallVerification.UNKNOWN -> "无法确认"
        }
    }

    private fun launchVerificationLabel(verification: LaunchVerification): String {
        return when (verification) {
            LaunchVerification.LAUNCHABLE -> "可启动"
            LaunchVerification.POLICY_LAUNCH_ONLY -> "策略入口启动"
            LaunchVerification.NOT_LAUNCHABLE -> "不可启动"
            LaunchVerification.UNKNOWN -> "未验证"
        }
    }

    private fun iconStatusLabel(status: IconStatus): String {
        return when (status) {
            IconStatus.OK -> "正常"
            IconStatus.MISSING -> "缺失"
            IconStatus.STALE -> "需要刷新"
        }
    }

    private fun confirmUninstall(app: AppInfo) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("卸载应用")
            .setMessage("确定要从隐藏空间中卸载「${app.appName}」吗？记录会保留，方便你确认状态后再移除。")
            .setPositiveButton("卸载") { _, _ -> uninstallApp(app) }
            .setNegativeButton("取消", null)
            .showSpace()
    }

    private fun uninstallApp(app: AppInfo) {
        pendingUninstallApp = app
        try {
            uninstallLauncher.launch(viewModel.createUninstallIntent(app))
        } catch (e: ActivityNotFoundException) {
            pendingUninstallApp = null
            showSpaceMessage("未找到卸载入口", error = true)
        } catch (e: SecurityException) {
            pendingUninstallApp = null
            showSpaceMessage("无法卸载，请检查工作资料权限", long = true, error = true)
        }
    }

    private fun confirmRemoveRecord(app: AppInfo) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("移除记录")
            .setMessage("这只会移除隐藏空间中的记录和图标缓存，不会卸载应用。")
            .setPositiveButton("移除") { _, _ ->
                viewModel.removeRecord(app)
                showSpaceMessage("记录已移除")
                findNavController().popBackStack()
            }
            .setNegativeButton("取消", null)
            .showSpace()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingUninstallApp = null
        _binding = null
    }
}
