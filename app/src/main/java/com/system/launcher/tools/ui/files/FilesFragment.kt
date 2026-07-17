package com.system.launcher.tools.ui.files

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.system.launcher.tools.R
import com.system.launcher.tools.databinding.FragmentFilesBinding
import com.system.launcher.tools.ui.common.MaterialActionDialogs
import com.system.launcher.tools.ui.common.SpaceUi
import com.system.launcher.tools.ui.common.showSpace
import com.system.launcher.tools.ui.common.showSpaceMessage
import com.system.launcher.tools.work.ProfileMediaTransferContract
import com.system.launcher.tools.work.WorkProfileManager
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class FilesFragment : Fragment() {

    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FilesViewModel by viewModels()

    @Inject
    lateinit var workProfileManager: WorkProfileManager

    private lateinit var imageAdapter: ImageGridAdapter
    private lateinit var fileAdapter: FileListAdapter
    private var selectedTab = FileTab.IMAGES
    private var latestState = FileSpaceState()
    private val selectedPaths = linkedSetOf<String>()
    private var pendingDeleteConfirmationItems: List<FileItem> = emptyList()
    private var pendingMoveDeletion: PendingMoveDeletion? = null
    private var selectionBackCallback: OnBackPressedCallback? = null
    private val tabScrollStates = mutableMapOf<FileTab, Parcelable>()

    private data class PendingMoveDeletion(
        val transferId: String,
        val copied: Int,
        val copyFailed: Int,
        val deletedBeforeConfirmation: Int = 0,
        val deleteFailedBeforeConfirmation: Int = 0
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (requiredPermissions().all { result[it] == true || hasPermission(it) }) {
            viewModel.loadFiles()
        } else {
            updatePermissionEmptyView()
        }
    }

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val items = pendingDeleteConfirmationItems
        pendingDeleteConfirmationItems = emptyList()
        val moveDeletion = pendingMoveDeletion
        pendingMoveDeletion = null
        if (result.resultCode == Activity.RESULT_OK && items.isNotEmpty()) {
            viewModel.verifyFilesDeleted(items) { deleted, failed ->
                clearSelection()
                if (moveDeletion == null) {
                    showDeleteSummary(deleted, failed)
                } else {
                    ProfileMediaTransferStore.clearPendingMove(requireContext(), moveDeletion.transferId)
                    showMoveSummary(
                        copied = moveDeletion.copied,
                        copyFailed = moveDeletion.copyFailed,
                        sourceDeleted = moveDeletion.deletedBeforeConfirmation + deleted,
                        sourceDeleteFailed = moveDeletion.deleteFailedBeforeConfirmation + failed
                    )
                }
            }
        } else {
            if (moveDeletion == null) {
                showSpaceMessage("已取消删除")
            } else {
                ProfileMediaTransferStore.clearPendingMove(requireContext(), moveDeletion.transferId)
                showMoveSummary(
                    copied = moveDeletion.copied,
                    copyFailed = moveDeletion.copyFailed,
                    sourceDeleted = moveDeletion.deletedBeforeConfirmation,
                    sourceDeleteFailed = moveDeletion.deleteFailedBeforeConfirmation + items.size
                )
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBackHandling()
        setupTabs()
        setupRecyclerView()
        setupActions()
        observeViewModel()
        ensurePermissionsAndLoad()
        SpaceUi.applySystemBarInsets(binding.pageContent)
        SpaceUi.reveal(binding.pageContent)
    }

    override fun onResume() {
        super.onResume()
        if (hasAllRequiredPermissions()) viewModel.loadFiles()
        binding.root.post { handleCompletedMove() }
    }

    private fun setupBackHandling() {
        selectionBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                clearSelection()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBackCallback!!)
    }

    private fun setupTabs() {
        SpaceUi.setSafeClickListener(binding.btnImages) { selectFileTab(FileTab.IMAGES) }
        SpaceUi.setSafeClickListener(binding.btnVideos) { selectFileTab(FileTab.VIDEOS) }
        SpaceUi.setSafeClickListener(binding.btnAllFiles) { selectFileTab(FileTab.ALL) }
        SpaceUi.attachPressScale(binding.btnImages, 0.98f)
        SpaceUi.attachPressScale(binding.btnVideos, 0.98f)
        SpaceUi.attachPressScale(binding.btnAllFiles, 0.98f)
        renderTabSelection()
    }

    private fun selectFileTab(tab: FileTab) {
        if (selectedTab == tab) return
        binding.rvFiles.layoutManager?.onSaveInstanceState()?.let { state ->
            tabScrollStates[selectedTab] = state
        }
        selectedTab = tab
        renderTabSelection()
        clearSelection()
        renderContent(latestState, restoreTabScroll = true)
    }

    private fun renderTabSelection() {
        binding.btnImages.isSelected = selectedTab == FileTab.IMAGES
        binding.btnVideos.isSelected = selectedTab == FileTab.VIDEOS
        binding.btnAllFiles.isSelected = selectedTab == FileTab.ALL
    }

    private fun setupRecyclerView() {
        imageAdapter = ImageGridAdapter(
            onClick = { item -> handleItemClick(item) },
            onLongClick = { item -> enterSelectionWith(item); true },
            onDateSelectAll = { dateKey -> selectDateGroup(dateKey) }
        )
        fileAdapter = FileListAdapter(
            onClick = { item -> handleItemClick(item) },
            onLongClick = { item -> enterSelectionWith(item); true },
            onDateSelectAll = { dateKey -> selectDateGroup(dateKey) }
        )
        SpaceUi.configureList(binding.rvFiles)
        binding.rvFiles.itemAnimator = null
        binding.rvFiles.setHasFixedSize(true)
    }

    private fun setupActions() {
        SpaceUi.setSafeClickListener(binding.btnFileBack) { findNavController().popBackStack() }
        SpaceUi.setSafeClickListener(binding.btnRefresh) {
            clearSelection()
            ensurePermissionsAndLoad()
        }
        SpaceUi.setSafeClickListener(binding.btnCancelSelection) { clearSelection() }
        SpaceUi.setSafeClickListener(binding.btnSelectAll) { toggleSelectAll() }
        SpaceUi.setSafeClickListener(binding.btnCopyToPersonal) { showTransferOptions() }
        SpaceUi.setSafeClickListener(binding.btnDeleteSelected) { confirmDeleteSelected() }
        SpaceUi.attachPressScale(binding.btnFileBack, 0.9f)
        SpaceUi.attachPressScale(binding.btnRefresh, 0.9f)
        SpaceUi.attachPressScale(binding.btnCancelSelection, 0.9f)
        SpaceUi.attachPressScale(binding.btnSelectAll, 0.9f)
        SpaceUi.attachPressScale(binding.btnCopyToPersonal, 0.9f)
        SpaceUi.attachPressScale(binding.btnDeleteSelected, 0.9f)
    }

    private fun observeViewModel() {
        viewModel.state.observe(viewLifecycleOwner) { state ->
            latestState = state
            binding.progressBar.visibility = if (state.loading) View.VISIBLE else View.GONE
            if (state.error != null) {
                showSpaceMessage(state.error, long = true, error = true)
            }
            pruneSelection(state)
            renderContent(state)
        }
    }

    private fun ensurePermissionsAndLoad() {
        if (hasAllRequiredPermissions()) {
            viewModel.loadFiles()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    private fun renderContent(state: FileSpaceState, restoreTabScroll: Boolean = false) {
        val items = currentItems(state)
        val renderedTab = selectedTab
        binding.tvFileSummary.text = buildSummary(state)

        if (selectedTab != FileTab.ALL) {
            if (binding.rvFiles.adapter !== imageAdapter) {
                binding.rvFiles.layoutManager = GridLayoutManager(requireContext(), 3).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int {
                            return if (imageAdapter.isDateHeader(position)) 3 else 1
                        }
                    }
                }
                binding.rvFiles.adapter = imageAdapter
            }
            imageAdapter.submitFiles(items) {
                if (restoreTabScroll) restoreScrollForTab(renderedTab)
            }
        } else {
            if (binding.rvFiles.adapter !== fileAdapter) {
                binding.rvFiles.layoutManager = LinearLayoutManager(requireContext())
                binding.rvFiles.adapter = fileAdapter
            }
            fileAdapter.submitFiles(items) {
                if (restoreTabScroll) restoreScrollForTab(renderedTab)
            }
        }

        val showEmpty = !state.loading && items.isEmpty()
        binding.emptyView.visibility = if (showEmpty) View.VISIBLE else View.GONE
        binding.rvFiles.visibility = if (showEmpty) View.GONE else View.VISIBLE
        if (showEmpty && hasAllRequiredPermissions()) updateEmptyText()
        renderSelectionState()
    }

    private fun restoreScrollForTab(tab: FileTab) {
        if (selectedTab != tab) return
        val savedState = tabScrollStates[tab]
        if (savedState == null) {
            binding.rvFiles.scrollToPosition(0)
        } else {
            binding.rvFiles.layoutManager?.onRestoreInstanceState(savedState)
        }
    }

    private fun currentItems(state: FileSpaceState = latestState): List<FileItem> {
        return when (selectedTab) {
            FileTab.IMAGES -> state.images
            FileTab.VIDEOS -> state.videos
            FileTab.ALL -> state.allFiles
        }
    }

    private fun buildSummary(state: FileSpaceState): String {
        val totalSize = state.allFiles.sumOf { it.sizeBytes }
        return "${state.allFiles.size} 个项目 · ${FileFormatters.formatSize(totalSize)}"
    }

    private fun updateEmptyText() {
        binding.tvEmptyTitle.text = when (selectedTab) {
            FileTab.IMAGES -> "暂无图片"
            FileTab.VIDEOS -> "暂无视频"
            FileTab.ALL -> "暂无文件"
        }
        binding.tvEmptySubtitle.text = "已读取工作资料内的 Pictures、Movies、Downloads、DCIM"
    }

    private fun updatePermissionEmptyView() {
        binding.progressBar.visibility = View.GONE
        binding.rvFiles.visibility = View.GONE
        binding.emptyView.visibility = View.VISIBLE
        binding.tvEmptyTitle.text = "需要文件读取权限"
        binding.tvEmptySubtitle.text = "允许后才能读取工作资料内的图片、视频和下载文件"
    }

    private fun handleItemClick(item: FileItem) {
        if (isSelectionMode()) {
            toggleSelection(item)
        } else {
            openFile(item)
        }
    }

    private fun enterSelectionWith(item: FileItem) {
        SpaceUi.haptic(binding.rvFiles)
        selectedPaths.add(item.path)
        renderSelectionState()
    }

    private fun toggleSelection(item: FileItem) {
        if (!selectedPaths.add(item.path)) selectedPaths.remove(item.path)
        renderSelectionState()
    }

    private fun toggleSelectAll() {
        val visiblePaths = currentItems().map { it.path }
        if (visiblePaths.isNotEmpty() && visiblePaths.all { selectedPaths.contains(it) }) {
            selectedPaths.removeAll(visiblePaths.toSet())
        } else {
            selectedPaths.addAll(visiblePaths)
        }
        renderSelectionState()
    }

    private fun selectDateGroup(dateKey: String) {
        val datePaths = currentItems()
            .filter { FileFormatters.dateSectionKey(it.modifiedAt) == dateKey }
            .map { it.path }
        if (datePaths.isEmpty()) return
        selectedPaths.addAll(datePaths)
        renderSelectionState()
    }

    private fun clearSelection() {
        if (selectedPaths.isEmpty()) {
            renderSelectionState()
            return
        }
        selectedPaths.clear()
        renderSelectionState()
    }

    private fun pruneSelection(state: FileSpaceState) {
        val existingPaths = state.allFiles.mapTo(hashSetOf()) { it.path }
        selectedPaths.retainAll(existingPaths)
    }

    private fun isSelectionMode(): Boolean = selectedPaths.isNotEmpty()

    private fun renderSelectionState() {
        val selectionMode = isSelectionMode()
        if (selectionMode && binding.selectionToolbar.visibility != View.VISIBLE) {
            SpaceUi.swap(binding.normalToolbar, binding.selectionToolbar)
        } else if (!selectionMode && binding.normalToolbar.visibility != View.VISIBLE) {
            SpaceUi.swap(binding.selectionToolbar, binding.normalToolbar)
        }
        binding.tvSelectionCount.text = "已选择 ${selectedPaths.size} 项"
        binding.btnDeleteSelected.isEnabled = selectedPaths.isNotEmpty()
        binding.btnCopyToPersonal.isEnabled = selectedPaths.isNotEmpty()
        imageAdapter.setSelectionState(selectedPaths, selectionMode)
        fileAdapter.setSelectionState(selectedPaths, selectionMode)
        selectionBackCallback?.isEnabled = selectionMode
    }

    private fun selectedItems(): List<FileItem> {
        val byPath = latestState.allFiles.associateBy { it.path }
        return selectedPaths.mapNotNull { byPath[it] }
    }

    private fun showTransferOptions() {
        val items = selectedItems()
        if (items.isEmpty()) return
        if (items.any { it.type == FileType.OTHER }) {
            showSpaceMessage("目前只支持传输图片和视频", error = true)
            return
        }
        if (items.size > ProfileMediaTransferContract.MAX_ITEMS_PER_TRANSFER) {
            showSpaceMessage(
                "单次最多传输 ${ProfileMediaTransferContract.MAX_ITEMS_PER_TRANSFER} 个文件",
                long = true,
                error = true
            )
            return
        }

        MaterialActionDialogs.show(
            requireContext(),
            getString(R.string.files_transfer_to_personal_title, items.size),
            listOf(
                MaterialActionDialogs.Action(
                    title = getString(R.string.files_transfer_copy_action),
                    iconRes = R.drawable.ic_copy_to_personal_24,
                    onClick = { startTransfer(items, ProfileMediaTransferContract.Operation.COPY) }
                ),
                MaterialActionDialogs.Action(
                    title = getString(R.string.files_transfer_move_action),
                    iconRes = R.drawable.ic_move_to_personal_24,
                    onClick = { confirmMove(items) }
                )
            )
        )
    }

    private fun confirmMove(items: List<FileItem>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.files_move_to_personal)
            .setMessage(getString(R.string.files_move_to_personal_confirm, items.size))
            .setPositiveButton(R.string.files_move_action) { _, _ ->
                startTransfer(items, ProfileMediaTransferContract.Operation.MOVE)
            }
            .setNegativeButton(R.string.files_transfer_cancel, null)
            .showSpace()
    }

    private fun startTransfer(items: List<FileItem>, operation: ProfileMediaTransferContract.Operation) {
        val uris = items.mapNotNull(::resolveTransferUri)
        if (uris.size != items.size) {
            showSpaceMessage("部分文件无法授权读取，请刷新后重试", long = true, error = true)
            return
        }
        val transferId = UUID.randomUUID().toString()
        val resultCallback = if (operation == ProfileMediaTransferContract.Operation.MOVE) {
            ProfileMediaTransferStore.savePendingMove(requireContext(), transferId, items)
            ProfileMediaTransferResultReceiver.createCallback(requireContext(), transferId)
        } else {
            null
        }
        val accepted = workProfileManager.startMediaTransferToPersonal(
            activity = requireActivity(),
            transferId = transferId,
            operation = operation,
            mediaUris = uris,
            resultCallback = resultCallback,
            onLaunchResult = { launched ->
                if (!isAdded) return@startMediaTransferToPersonal
                if (launched) {
                    clearSelection()
                } else {
                    if (operation == ProfileMediaTransferContract.Operation.MOVE) {
                        ProfileMediaTransferStore.clearPendingMove(requireContext(), transferId)
                    }
                    showSpaceMessage(
                        "无法打开主空间接收页面，请确认工作资料已启用",
                        long = true,
                        error = true
                    )
                }
            }
        )
        if (!accepted) {
            if (operation == ProfileMediaTransferContract.Operation.MOVE) {
                ProfileMediaTransferStore.clearPendingMove(requireContext(), transferId)
            }
            showSpaceMessage("无法打开主空间接收页面，请确认工作资料已启用", long = true, error = true)
        }
    }

    private fun handleCompletedMove() {
        if (!isAdded || pendingMoveDeletion != null) return
        val completed = ProfileMediaTransferStore.getCompletedMove(requireContext()) ?: return
        if (completed.copiedItems.isEmpty()) {
            ProfileMediaTransferStore.clearPendingMove(requireContext(), completed.transferId)
            showMoveSummary(0, completed.copyFailed, 0, 0)
            return
        }
        pendingMoveDeletion = PendingMoveDeletion(
            transferId = completed.transferId,
            copied = completed.copiedItems.size,
            copyFailed = completed.copyFailed
        )
        viewModel.deleteFiles(completed.copiedItems) { result ->
            if (result.needsConfirmation != null) {
                pendingMoveDeletion = pendingMoveDeletion?.copy(
                    deletedBeforeConfirmation = result.deleted,
                    deleteFailedBeforeConfirmation = result.failed
                )
                launchDeleteConfirmation(result.needsConfirmation)
            } else {
                pendingMoveDeletion = null
                ProfileMediaTransferStore.clearPendingMove(requireContext(), completed.transferId)
                clearSelection()
                showMoveSummary(
                    copied = completed.copiedItems.size,
                    copyFailed = completed.copyFailed,
                    sourceDeleted = result.deleted,
                    sourceDeleteFailed = result.failed
                )
            }
        }
    }

    private fun showMoveSummary(
        copied: Int,
        copyFailed: Int,
        sourceDeleted: Int,
        sourceDeleteFailed: Int
    ) {
        val outcome = MediaMoveOutcome(copied, copyFailed, sourceDeleted, sourceDeleteFailed)
        val message = when {
            outcome.moved > 0 && !outcome.hasFailures ->
                getString(R.string.files_move_complete, outcome.moved)
            outcome.moved > 0 ->
                getString(
                    R.string.files_move_partial,
                    outcome.moved,
                    outcome.copiedButRetained,
                    outcome.copyFailed
                )
            outcome.copiedButRetained > 0 ->
                getString(R.string.files_move_sources_retained, outcome.copiedButRetained)
            else -> getString(R.string.files_move_failed_sources_retained)
        }
        showSpaceMessage(message, long = outcome.hasFailures, error = outcome.hasFailures)
    }

    private fun resolveTransferUri(item: FileItem): Uri? {
        item.contentUri
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { it.scheme == "content" }
            ?.let { return it }
        val file = File(item.path)
        if (!file.exists() || !file.canRead()) return null
        return runCatching { file.toContentUri() }.getOrNull()
    }

    private fun confirmDeleteSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除文件")
            .setMessage("确定删除选中的 ${items.size} 个项目吗？")
            .setPositiveButton("删除") { _, _ -> deleteItems(items) }
            .setNegativeButton("取消", null)
            .showSpace()
    }

    private fun deleteItems(items: List<FileItem>) {
        viewModel.deleteFiles(items) { result ->
            if (result.needsConfirmation != null) {
                if (result.deleted > 0 || result.failed > 0) showDeleteSummary(result.deleted, result.failed)
                launchDeleteConfirmation(result.needsConfirmation)
            } else {
                clearSelection()
                showDeleteSummary(result.deleted, result.failed)
            }
        }
    }

    private fun showDeleteSummary(deleted: Int, failed: Int) {
        val message = when {
            failed == 0 && deleted > 0 -> "已删除 $deleted 个项目"
            deleted > 0 -> "已删除 $deleted 个项目，$failed 个删除失败"
            else -> "删除失败"
        }
        showSpaceMessage(message, error = failed > 0 || deleted == 0)
    }

    private fun openFile(item: FileItem) {
        when (item.type) {
            FileType.IMAGE -> openImagePreview(item, latestState.images)
            FileType.VIDEO -> openVideo(item)
            FileType.OTHER -> openWithSystemViewer(item)
        }
    }

    private fun openImagePreview(item: FileItem, images: List<FileItem>) {
        val imagePaths = images.map { it.path }
        val startIndex = imagePaths.indexOf(item.path).coerceAtLeast(0)
        val intent = Intent(requireContext(), ImagePreviewActivity::class.java).apply {
            putStringArrayListExtra(ImagePreviewActivity.EXTRA_IMAGE_PATHS, ArrayList(imagePaths))
            putExtra(ImagePreviewActivity.EXTRA_START_INDEX, startIndex)
        }
        startActivity(intent)
    }

    private fun openVideo(item: FileItem) {
        openWithSystemViewer(item, "video/*")
    }

    private fun openWithSystemViewer(item: FileItem, fallbackMimeType: String = "*/*") {
        val file = File(item.path)
        val uri = file.toContentUri()
        val mimeType = resolveMimeType(file) ?: fallbackMimeType
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showSpaceMessage("没有可打开该文件的应用", error = true)
        } catch (e: SecurityException) {
            showSpaceMessage("无法授权系统应用读取该文件", error = true)
        }
    }

    private fun launchDeleteConfirmation(result: FilesViewModel.BatchDeleteResult.NeedsConfirmation) {
        pendingDeleteConfirmationItems = result.items
        runCatching {
            deleteRequestLauncher.launch(
                IntentSenderRequest.Builder(result.intentSender).build()
            )
        }.onFailure {
            pendingDeleteConfirmationItems = emptyList()
            val moveDeletion = pendingMoveDeletion
            pendingMoveDeletion = null
            if (moveDeletion == null) {
                showSpaceMessage("无法打开删除确认", error = true)
            } else {
                ProfileMediaTransferStore.clearPendingMove(requireContext(), moveDeletion.transferId)
                showMoveSummary(
                    copied = moveDeletion.copied,
                    copyFailed = moveDeletion.copyFailed,
                    sourceDeleted = moveDeletion.deletedBeforeConfirmation,
                    sourceDeleteFailed = moveDeletion.deleteFailedBeforeConfirmation + result.items.size
                )
            }
        }
    }

    private fun requiredPermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    }

    private fun hasAllRequiredPermissions(): Boolean {
        return requiredPermissions().all { hasPermission(it) }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun File.toContentUri(): Uri {
        return FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            this
        )
    }

    private fun resolveMimeType(file: File): String? {
        val extension = file.extension.takeIf { it.isNotBlank() } ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

