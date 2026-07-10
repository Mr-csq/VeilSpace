package com.system.launcher.tools.ui.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.recyclerview.widget.GridLayoutManager
import com.system.launcher.tools.R
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.model.InstallVerification
import com.system.launcher.tools.databinding.FragmentHomeBinding
import com.system.launcher.tools.work.ProfilePackageMonitor
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()

    private lateinit var appGridAdapter: AppGridAdapter
    private var pendingApkUri: Uri? = null
    private var packageMonitor: ProfilePackageMonitor? = null

    private val apkPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) handleSelectedApk(uri)
    }

    private val unknownSourcesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = pendingApkUri ?: return@registerForActivityResult
        if (viewModel.canRequestPackageInstalls()) {
            launchApkInstaller(uri)
        } else {
            pendingApkUri = null
            Toast.makeText(requireContext(), "请允许本应用安装未知来源应用后重试", Toast.LENGTH_LONG).show()
        }
    }

    private val externalActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.finalizePendingApkInstall()
        viewModel.loadProfileApps()
    }

    private val deleteDocumentsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = collectResultUris(result.data)
        if (uris.isEmpty()) return@registerForActivityResult
        viewModel.deleteDocuments(uris) { deleted, failed ->
            val message = if (failed == 0) "已删除 $deleted 个文件" else "已删除 $deleted 个文件，$failed 个删除失败"
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        packageMonitor = ProfilePackageMonitor(requireContext()).also { it.start() }
        setupRecyclerView()
        setupActions()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        packageMonitor?.start()
        viewModel.loadProfileApps()
    }

    private fun setupRecyclerView() {
        appGridAdapter = AppGridAdapter(
            onAppClick = { app -> launchApp(app) },
            onAppLongClick = { app ->
                showHomeAppActions(app)
                true
            }
        )
        binding.rvApps.apply {
            layoutManager = GridLayoutManager(requireContext(), 4)
            adapter = appGridAdapter
        }
    }

    private fun setupActions() {
        binding.btnAddApp.setOnClickListener { openApkPickerWithValidation() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun showSettingsDialog() {
        val options = arrayOf(
            "应用管理",
            "修复应用图标",
            "整理桌面残留图标",
            "修复安装环境",
            "本应用未知来源授权"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> findNavController().navigate(R.id.action_home_to_app_management)
                    1 -> repairAppIcons()
                    2 -> tidyDesktopResidualIcons()
                    3 -> repairInstallEnvironment()
                    4 -> launchUnknownAppSourcesSettings()
                }
            }
            .show()
    }

    private fun repairAppIcons() {
        Toast.makeText(requireContext(), "正在修复应用图标", Toast.LENGTH_SHORT).show()
        viewModel.repairHomeAppIcons { repaired ->
            val message = if (repaired) "应用图标已更新" else "没有可修复的应用图标"
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun tidyDesktopResidualIcons() {
        Toast.makeText(requireContext(), "正在整理桌面残留图标", Toast.LENGTH_SHORT).show()
        viewModel.tidyDesktopResidualIcons { hiddenCount ->
            Toast.makeText(requireContext(), "已处理 $hiddenCount 个隐藏空间应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchUnknownAppSourcesSettings() {
        val intent = viewModel.createUnknownAppSourcesIntent()
        if (intent == null) {
            Toast.makeText(requireContext(), "当前系统不支持从此入口授权未知来源", Toast.LENGTH_SHORT).show()
        } else {
            launchExternalIntent(intent)
        }
    }

    private fun repairInstallEnvironment() {
        Toast.makeText(requireContext(), "正在修复安装环境", Toast.LENGTH_SHORT).show()
        viewModel.repairProfileInstallEnvironment { success ->
            val message = if (success) "安装环境已修复" else "修复失败，请确认当前在工作资料入口"
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            if (success) viewModel.loadProfileApps()
        }
    }

    private fun observeViewModel() {
        viewModel.profileApps.observe(viewLifecycleOwner) { apps ->
            appGridAdapter.submitList(apps)
            binding.tvHomeSubtitle.text = "${apps.size} 个首页应用"
            updateEmptyView(apps.isEmpty())
        }
    }

    private fun updateEmptyView(isEmpty: Boolean) {
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvApps.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun launchApp(app: AppInfo) {
        if (viewModel.isInternalFileManagerApp(app)) {
            findNavController().navigate(R.id.action_home_to_files)
            return
        }
        if (!viewModel.canAttemptLaunch(app)) {
            showUnavailableAppDialog(app)
            return
        }
        val success = viewModel.launchApp(app)
        if (!success) showUnavailableAppDialog(app)
    }

    private fun showUnavailableAppDialog(app: AppInfo) {
        val reason = app.diagnosticReason.ifBlank { "当前无法启动此应用" }
        AlertDialog.Builder(requireContext())
            .setTitle(app.appName)
            .setMessage(reason)
            .setPositiveButton("应用管理") { _, _ -> findNavController().navigate(R.id.action_home_to_app_management) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showHomeAppActions(app: AppInfo) {
        val options = if (viewModel.isFileManagerApp(app)) {
            arrayOf("管理此应用", "从首页隐藏", "选择文件删除")
        } else {
            arrayOf("管理此应用", "从首页隐藏")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(app.appName)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "管理此应用" -> findNavController().navigate(R.id.action_home_to_app_management)
                    "从首页隐藏" -> viewModel.hideFromHome(app) {
                        Toast.makeText(requireContext(), "已从首页隐藏", Toast.LENGTH_SHORT).show()
                    }
                    "选择文件删除" -> launchDeleteDocumentsPicker()
                }
            }
            .show()
    }

    private fun openApkPickerWithValidation() {
        val validationError = viewModel.validateInstallEntry()
        if (validationError != null) {
            Toast.makeText(requireContext(), validationError, Toast.LENGTH_SHORT).show()
            return
        }
        openApkPicker()
    }

    private fun openApkPicker() {
        try {
            apkPickerLauncher.launch("application/vnd.android.package-archive")
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(requireContext(), "无法打开文件选择器，请检查工作资料权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSelectedApk(uri: Uri) {
        pendingApkUri = uri
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.prepareApkInstallCandidate(uri) {
            if (!isAdded || _binding == null) return@prepareApkInstallCandidate
            continueSelectedApkInstall(uri)
        }
    }

    private fun continueSelectedApkInstall(uri: Uri) {
        val diagnostic = viewModel.getPendingApkInstallDiagnostic()
        if (diagnostic != null) {
            val blocked = viewModel.isPendingApkInstallBlocked()
            val builder = AlertDialog.Builder(requireContext())
                .setTitle(if (blocked) "无法安装此 APK" else "安装包可能无法安装")
                .setMessage(diagnostic)
            if (blocked) {
                builder.setPositiveButton("知道了") { _, _ -> viewModel.clearPendingApkInstallCandidate() }
            } else {
                builder
                    .setPositiveButton("仍然尝试") { _, _ -> launchSelectedApkInstall(uri) }
                    .setNegativeButton("取消") { _, _ -> viewModel.clearPendingApkInstallCandidate() }
            }
            builder.show()
            return
        }
        launchSelectedApkInstall(uri)
    }

    private fun launchSelectedApkInstall(uri: Uri) {
        if (viewModel.canRequestPackageInstalls()) {
            launchApkInstaller(uri)
            return
        }
        val settingsIntent = viewModel.createUnknownAppSourcesIntent()
        if (settingsIntent == null) {
            Toast.makeText(requireContext(), "当前系统不支持从此入口安装 APK", Toast.LENGTH_SHORT).show()
        } else {
            unknownSourcesLauncher.launch(settingsIntent)
        }
    }

    private fun launchApkInstaller(uri: Uri) {
        launchExternalIntent(viewModel.createApkInstallIntent(uri))
        pendingApkUri = null
    }

    private fun launchExternalIntent(intent: Intent) {
        try {
            externalActivityLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "未找到可处理该操作的应用", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(requireContext(), "启动失败，请检查工作资料权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchDeleteDocumentsPicker() {
        try {
            deleteDocumentsLauncher.launch(viewModel.createDeleteDocumentsIntent())
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun collectResultUris(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val result = mutableListOf<Uri>()
        data.data?.let { result.add(it) }
        val clipData = data.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri?.let { result.add(it) }
            }
        }
        return result.distinct()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        packageMonitor?.stop()
        packageMonitor = null
        _binding = null
    }
}


