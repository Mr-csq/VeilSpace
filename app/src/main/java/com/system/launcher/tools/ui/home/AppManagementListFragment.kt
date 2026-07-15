package com.system.launcher.tools.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.system.launcher.tools.R
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.model.InstallVerification
import com.system.launcher.tools.databinding.FragmentAppManagementListBinding
import com.system.launcher.tools.ui.common.SpaceUi
import com.system.launcher.tools.ui.common.showSpaceMessage
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AppManagementListFragment : Fragment() {
    private var _binding: FragmentAppManagementListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AppManagementViewModel by viewModels()
    private lateinit var adapter: AppManagementAdapter
    private var latestApps: List<AppInfo> = emptyList()
    private lateinit var mode: AppManagementMode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = runCatching {
            AppManagementMode.valueOf(requireArguments().getString(ARG_MODE).orEmpty())
        }.getOrDefault(AppManagementMode.ALL_APPS)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAppManagementListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupHeader()
        setupList()
        setupActions()
        observeViewModel()
        SpaceUi.applySystemBarInsets(binding.pageContent)
        SpaceUi.reveal(binding.pageContent)
    }

    private fun setupHeader() {
        when (mode) {
            AppManagementMode.ALL_APPS -> {
                binding.tvListEyebrow.text = "APP DIRECTORY"
                binding.tvListTitle.text = "应用设置"
                binding.tvListSubtitle.text = "管理首页显示、后台策略、卸载与应用状态"
            }
            AppManagementMode.ISSUES -> {
                binding.tvListEyebrow.text = "ISSUE CENTER"
                binding.tvListTitle.text = "问题处理"
                binding.tvListSubtitle.text = "集中处理缺失图标、不可启动和卸载记录"
            }
        }
    }

    private fun setupList() {
        adapter = AppManagementAdapter(onAppClick = { app -> openDetail(app) })
        adapter.setMode(mode)
        binding.rvManagementApps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvManagementApps.adapter = adapter
        SpaceUi.configureList(binding.rvManagementApps)
    }

    private fun setupActions() {
        SpaceUi.setSafeClickListener(binding.btnBack) { findNavController().popBackStack() }
        SpaceUi.setSafeClickListener(binding.btnRefresh) {
            showSpaceMessage("正在刷新应用状态")
            viewModel.refreshAll()
        }
        SpaceUi.attachPressScale(binding.btnBack, 0.9f)
        SpaceUi.attachPressScale(binding.btnRefresh, 0.9f)
    }

    private fun observeViewModel() {
        viewModel.apps.observe(viewLifecycleOwner) { apps ->
            latestApps = apps
            renderApps()
        }
        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            binding.tvLoading.visibility = if (loading) View.VISIBLE else View.GONE
        }
    }

    private fun renderApps() {
        if (_binding == null) return
        val apps = appsForMode(latestApps)
        adapter.submitApps(apps)
        binding.tvModeHint.text = when (mode) {
            AppManagementMode.ALL_APPS -> "点击应用进入详情，共 ${apps.size} 个记录"
            AppManagementMode.ISSUES -> "当前共有 ${apps.size} 个需要处理的应用记录"
        }
        updateEmptyView(apps.isEmpty())
    }

    private fun appsForMode(apps: List<AppInfo>): List<AppInfo> {
        return when (mode) {
            AppManagementMode.ALL_APPS -> apps.sortedWith(managementComparator())
            AppManagementMode.ISSUES -> apps
                .filter { AppManagementAdapter.hasIssue(it) }
                .sortedWith(managementComparator())
        }
    }

    private fun managementComparator(): Comparator<AppInfo> {
        return compareBy<AppInfo> { managementGroup(it) }
            .thenBy { if (it.showOnHome) it.sortOrder else Int.MAX_VALUE }
            .thenBy { it.appName.lowercase() }
    }

    private fun managementGroup(app: AppInfo): Int {
        return when {
            app.showOnHome && app.installVerification != InstallVerification.CONFIRMED_MISSING -> 0
            !AppManagementAdapter.hasIssue(app) -> 1
            app.installVerification == InstallVerification.CONFIRMED_MISSING -> 3
            else -> 2
        }
    }

    private fun updateEmptyView(empty: Boolean) {
        binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvManagementApps.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvEmptyTitle.text = when (mode) {
            AppManagementMode.ALL_APPS -> "暂无应用记录"
            AppManagementMode.ISSUES -> "暂无问题应用"
        }
        binding.tvEmptyMessage.text = when (mode) {
            AppManagementMode.ALL_APPS -> "安装应用后会出现在这里，也可以点击右上角刷新。"
            AppManagementMode.ISSUES -> "当前没有需要处理的应用状态。"
        }
    }

    private fun openDetail(app: AppInfo) {
        findNavController().navigate(
            R.id.action_app_management_list_to_app_management_detail,
            bundleOf(ARG_PACKAGE_NAME to app.packageName)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_MODE = "mode"
        const val ARG_PACKAGE_NAME = "packageName"
    }
}
