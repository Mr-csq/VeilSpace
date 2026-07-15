package com.system.launcher.tools.ui.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.system.launcher.tools.R
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.databinding.FragmentHomeBinding
import com.system.launcher.tools.ui.common.MaterialActionDialogs
import com.system.launcher.tools.ui.common.SpaceUi
import com.system.launcher.tools.ui.common.showSpace
import com.system.launcher.tools.ui.common.showSpaceMessage
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
    private var dragChanged = false
    private var settingsDialog: BottomSheetDialog? = null

    private val apkPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) handleSelectedApk(uri)
    }

    private val unknownSourcesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = pendingApkUri ?: return@registerForActivityResult
        if (viewModel.canRequestPackageInstalls()) {
            launchApkInstaller(uri)
        } else {
            pendingApkUri = null
            showSpaceMessage("请允许本应用安装未知来源应用后重试", long = true, error = true)
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
            showSpaceMessage(message, error = failed > 0)
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
        SpaceUi.applySystemBarInsets(binding.pageContent)
        SpaceUi.reveal(binding.pageContent)
    }

    override fun onResume() {
        super.onResume()
        packageMonitor?.start()
        viewModel.loadProfileApps()
    }

    private fun setupRecyclerView() {
        appGridAdapter = AppGridAdapter(onAppClick = { app -> launchApp(app) })

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = true
            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                if (appGridAdapter.itemCount <= 1) return 0
                return makeMovementFlags(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                    0
                )
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) return false
                val moved = appGridAdapter.moveItem(fromPosition, toPosition)
                dragChanged = dragChanged || moved
                return moved
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.let(SpaceUi::haptic)
                    viewHolder?.itemView?.translationZ = 12f
                    viewHolder?.itemView?.animate()?.scaleX(1.055f)?.scaleY(1.055f)?.setDuration(140)?.start()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.translationZ = 0f
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(240).start()
                if (!dragChanged) return
                dragChanged = false
                viewModel.reorderHomeApps(appGridAdapter.finishDragAndGetPackageOrder())
            }
        })

        binding.rvApps.apply {
            layoutManager = GridLayoutManager(requireContext(), 4)
            adapter = appGridAdapter
            SpaceUi.configureList(this)
        }
        itemTouchHelper.attachToRecyclerView(binding.rvApps)
    }

    private fun setupActions() {
        SpaceUi.setSafeClickListener(binding.btnAddApp) { openApkPickerWithValidation() }
        SpaceUi.setSafeClickListener(binding.btnSettings) { showSettingsDialog() }
        SpaceUi.attachPressScale(binding.btnAddApp, 0.9f)
        SpaceUi.attachPressScale(binding.btnSettings, 0.9f)
    }

    private fun showSettingsDialog() {
        if (settingsDialog?.isShowing == true) return
        settingsDialog = MaterialActionDialogs.show(
            context = requireContext(),
            title = "设置",
            actions = listOf(
                MaterialActionDialogs.Action("隐藏空间设置", R.drawable.ic_settings_24) {
                    findNavController().navigate(R.id.action_home_to_app_management)
                },
                MaterialActionDialogs.Action("修复应用图标", R.drawable.ic_repair_24) { repairAppIcons() },
                MaterialActionDialogs.Action("整理桌面残留图标", R.drawable.ic_folder_24) { tidyDesktopResidualIcons() },
                MaterialActionDialogs.Action("修复安装环境", R.drawable.ic_shield_24) { repairInstallEnvironment() },
                MaterialActionDialogs.Action("本应用未知来源授权", R.drawable.ic_info_24) { launchUnknownAppSourcesSettings() }
            )
        )
        settingsDialog?.setOnDismissListener { settingsDialog = null }
    }
    private fun repairAppIcons() {
        showSpaceMessage("正在修复应用图标")
        viewModel.repairHomeAppIcons { repaired ->
            val message = if (repaired) "应用图标已更新" else "没有可修复的应用图标"
            showSpaceMessage(message)
        }
    }

    private fun tidyDesktopResidualIcons() {
        showSpaceMessage("正在整理桌面残留图标")
        viewModel.tidyDesktopResidualIcons { hiddenCount ->
            showSpaceMessage("已处理 $hiddenCount 个隐藏空间应用")
        }
    }

    private fun launchUnknownAppSourcesSettings() {
        val intent = viewModel.createUnknownAppSourcesIntent()
        if (intent == null) {
            showSpaceMessage("当前系统不支持从此入口授权未知来源", error = true)
        } else {
            launchExternalIntent(intent)
        }
    }

    private fun repairInstallEnvironment() {
        showSpaceMessage("正在修复安装环境")
        viewModel.repairProfileInstallEnvironment { success ->
            val message = if (success) "安装环境已修复" else "修复失败，请确认当前在工作资料入口"
            showSpaceMessage(message, long = true, error = !success)
            if (success) viewModel.loadProfileApps()
        }
    }

    private fun observeViewModel() {
        viewModel.profileApps.observe(viewLifecycleOwner) { apps ->
            appGridAdapter.submitApps(apps)
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(app.appName)
            .setMessage(reason)
            .setPositiveButton("隐藏空间设置") { _, _ -> findNavController().navigate(R.id.action_home_to_app_management) }
            .setNegativeButton("取消", null)
            .showSpace()
    }

    private fun openApkPickerWithValidation() {
        val validationError = viewModel.validateInstallEntry()
        if (validationError != null) {
            showSpaceMessage(validationError, error = true)
            return
        }
        openApkPicker()
    }

    private fun openApkPicker() {
        try {
            apkPickerLauncher.launch("application/vnd.android.package-archive")
        } catch (e: ActivityNotFoundException) {
            showSpaceMessage("无法打开文件选择器", error = true)
        } catch (e: SecurityException) {
            showSpaceMessage("无法打开文件选择器，请检查工作资料权限", long = true, error = true)
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
            val builder = MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (blocked) "无法安装此 APK" else "安装包可能无法安装")
                .setMessage(diagnostic)
            if (blocked) {
                builder.setPositiveButton("知道了") { _, _ -> viewModel.clearPendingApkInstallCandidate() }
            } else {
                builder
                    .setPositiveButton("仍然尝试") { _, _ -> launchSelectedApkInstall(uri) }
                    .setNegativeButton("取消") { _, _ -> viewModel.clearPendingApkInstallCandidate() }
            }
            builder.showSpace()
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
            showSpaceMessage("当前系统不支持从此入口安装 APK", error = true)
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
            showSpaceMessage("未找到可处理该操作的应用", error = true)
        } catch (e: SecurityException) {
            showSpaceMessage("启动失败，请检查工作资料权限", error = true)
        }
    }

    private fun launchDeleteDocumentsPicker() {
        try {
            deleteDocumentsLauncher.launch(viewModel.createDeleteDocumentsIntent())
        } catch (e: ActivityNotFoundException) {
            showSpaceMessage("无法打开文件选择器", error = true)
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
        settingsDialog?.dismiss()
        settingsDialog = null
        packageMonitor?.stop()
        packageMonitor = null
        _binding = null
    }
}

