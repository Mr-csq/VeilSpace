package com.system.launcher.tools.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.system.launcher.tools.R
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.model.InstallVerification
import com.system.launcher.tools.databinding.FragmentAppManagementBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AppManagementFragment : Fragment() {
    private var _binding: FragmentAppManagementBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AppManagementViewModel by viewModels()
    private lateinit var adapter: AppManagementAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var latestApps: List<AppInfo> = emptyList()
    private var currentMode: AppManagementMode = AppManagementMode.HOME_ORDER
    private var dragChanged = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAppManagementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupList()
        setupActions()
        observeViewModel()
    }

    private fun setupList() {
        adapter = AppManagementAdapter(
            onAppClick = { app -> openDetail(app) },
            onDragStart = { holder ->
                if (currentMode == AppManagementMode.HOME_ORDER) itemTouchHelper.startDrag(holder)
            }
        )

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled(): Boolean = false
            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                if (currentMode != AppManagementMode.HOME_ORDER) return 0
                return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            }

            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val moved = adapter.moveItem(viewHolder.adapterPosition, target.adapterPosition)
                dragChanged = dragChanged || moved
                return moved
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (!dragChanged || currentMode != AppManagementMode.HOME_ORDER) return
                dragChanged = false
                viewModel.reorderHomeApps(adapter.finishDragAndGetPackageOrder())
            }
        })

        binding.rvManagementApps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvManagementApps.adapter = adapter
        itemTouchHelper.attachToRecyclerView(binding.rvManagementApps)
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnRefresh.setOnClickListener {
            Toast.makeText(requireContext(), "正在刷新应用状态", Toast.LENGTH_SHORT).show()
            viewModel.refreshAll()
        }
        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentMode = when (checkedId) {
                R.id.btn_all_apps -> AppManagementMode.ALL_APPS
                R.id.btn_issue_apps -> AppManagementMode.ISSUES
                else -> AppManagementMode.HOME_ORDER
            }
            renderApps()
        }
        binding.modeGroup.check(R.id.btn_home_order)
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
        adapter.setMode(currentMode)
        adapter.submitApps(apps)
        updateModeHint(apps.size)
        updateEmptyView(apps.isEmpty())
    }

    private fun appsForMode(apps: List<AppInfo>): List<AppInfo> {
        return when (currentMode) {
            AppManagementMode.HOME_ORDER -> apps
                .filter { it.showOnHome && it.installVerification != InstallVerification.CONFIRMED_MISSING }
                .sortedWith(compareBy<AppInfo> { it.sortOrder }.thenBy { it.appName.lowercase() })
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

    private fun updateModeHint(count: Int) {
        binding.tvModeHint.text = when (currentMode) {
            AppManagementMode.HOME_ORDER -> "拖动右侧手柄调整首页顺序，共 $count 个首页应用"
            AppManagementMode.ALL_APPS -> "点击应用进入详情页管理显示、后台、卸载和状态，共 $count 个记录"
            AppManagementMode.ISSUES -> "集中处理图标缺失、不可启动、待验证和已卸载记录，共 $count 个"
        }
    }

    private fun updateEmptyView(empty: Boolean) {
        binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvManagementApps.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvEmptyTitle.text = when (currentMode) {
            AppManagementMode.HOME_ORDER -> "首页暂无应用"
            AppManagementMode.ALL_APPS -> "暂无应用记录"
            AppManagementMode.ISSUES -> "暂无问题应用"
        }
        binding.tvEmptyMessage.text = when (currentMode) {
            AppManagementMode.HOME_ORDER -> "进入全部应用，打开某个应用的首页显示开关。"
            AppManagementMode.ALL_APPS -> "安装应用后会出现在这里，也可以点击右上角刷新。"
            AppManagementMode.ISSUES -> "当前没有需要处理的应用状态。"
        }
    }

    private fun openDetail(app: AppInfo) {
        findNavController().navigate(
            R.id.action_app_management_to_app_management_detail,
            bundleOf(ARG_PACKAGE_NAME to app.packageName)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_PACKAGE_NAME = "packageName"
    }
}

