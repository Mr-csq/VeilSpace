package com.system.launcher.tools.ui.home

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.databinding.FragmentAppManagementBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AppManagementFragment : Fragment() {
    private var _binding: FragmentAppManagementBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AppManagementViewModel by viewModels()
    private lateinit var adapter: AppManagementAdapter
    private var pendingUninstallApp: AppInfo? = null

    private val uninstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val app = pendingUninstallApp ?: return@registerForActivityResult
        pendingUninstallApp = null
        viewModel.finalizeUninstall(app) { removed ->
            val message = if (removed) "应用已卸载，记录已标记为未安装" else "卸载未完成，应用仍在隐藏空间中"
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

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
            onShowChanged = { app, show -> viewModel.setShowOnHome(app, show) },
            onKeepAliveChanged = { app, keepAlive -> viewModel.setKeepAlive(app, keepAlive) },
            onMoveUp = { app -> viewModel.move(app, -1) },
            onMoveDown = { app -> viewModel.move(app, 1) },
            onManage = { app -> showAppActions(app) }
        )
        binding.rvManagementApps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvManagementApps.adapter = adapter
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnRefresh.setOnClickListener {
            Toast.makeText(requireContext(), "正在刷新应用状态", Toast.LENGTH_SHORT).show()
            viewModel.refreshAll()
        }
    }

    private fun observeViewModel() {
        viewModel.apps.observe(viewLifecycleOwner) { apps ->
            adapter.submitList(apps)
            val empty = apps.isEmpty()
            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.rvManagementApps.visibility = if (empty) View.GONE else View.VISIBLE
        }
        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            binding.tvLoading.visibility = if (loading) View.VISIBLE else View.GONE
        }
    }

    private fun showAppActions(app: AppInfo) {
        val options = if (viewModel.isInternalFileManagerApp(app)) {
            arrayOf("查看状态", "从首页隐藏")
        } else {
            arrayOf("查看状态", "刷新图标", "卸载应用", "移除记录")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(app.appName)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "查看状态" -> showStatus(app)
                    "刷新图标" -> {
                        viewModel.refreshIcon(app)
                        Toast.makeText(requireContext(), "正在刷新图标", Toast.LENGTH_SHORT).show()
                    }
                    "卸载应用" -> confirmUninstall(app)
                    "移除记录" -> confirmRemoveRecord(app)
                    "从首页隐藏" -> viewModel.setShowOnHome(app, false)
                }
            }
            .show()
    }

    private fun showStatus(app: AppInfo) {
        val message = buildString {
            appendLine("包名：${app.packageName}")
            appendLine("首页显示：${if (app.showOnHome) "是" else "否"}")
            appendLine("后台运行：${if (app.keepAlive) "允许" else "自动隐藏"}")
            appendLine("安装状态：${if (app.installed) "已安装" else "未安装"}")
            appendLine("启动状态：${if (app.launchable) "可启动" else "不可启动"}")
            appendLine("图标状态：${app.iconStatus}")
            if (app.diagnosticReason.isNotBlank()) appendLine("原因：${app.diagnosticReason}")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(app.appName)
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun confirmUninstall(app: AppInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle("卸载应用")
            .setMessage("确定要从隐藏空间中卸载「${app.appName}」吗？记录会保留，方便你确认状态后再移除。")
            .setPositiveButton("卸载") { _, _ -> uninstallApp(app) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun uninstallApp(app: AppInfo) {
        pendingUninstallApp = app
        try {
            uninstallLauncher.launch(viewModel.createUninstallIntent(app))
        } catch (e: ActivityNotFoundException) {
            pendingUninstallApp = null
            Toast.makeText(requireContext(), "未找到卸载入口", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            pendingUninstallApp = null
            Toast.makeText(requireContext(), "无法卸载，请检查工作资料权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmRemoveRecord(app: AppInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle("移除记录")
            .setMessage("这只会移除隐藏空间中的记录和图标缓存，不会卸载应用。")
            .setPositiveButton("移除") { _, _ -> viewModel.removeRecord(app) }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingUninstallApp = null
        _binding = null
    }
}
