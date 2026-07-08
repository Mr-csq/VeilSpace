package com.system.launcher.tools.ui.files

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.system.launcher.tools.databinding.FragmentFilesBinding
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

@AndroidEntryPoint
class FilesFragment : Fragment() {

    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FilesViewModel by viewModels()

    private lateinit var imageAdapter: ImageGridAdapter
    private lateinit var fileAdapter: FileListAdapter
    private var selectedTab = FileTab.IMAGES
    private var latestState = FileSpaceState()
    private val selectedPaths = linkedSetOf<String>()
    private var pendingDeleteConfirmationItems: List<FileItem> = emptyList()
    private var selectionBackCallback: OnBackPressedCallback? = null

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
        if (result.resultCode == Activity.RESULT_OK && items.isNotEmpty()) {
            viewModel.verifyFilesDeleted(items) { deleted, failed ->
                clearSelection()
                showDeleteSummary(deleted, failed)
            }
        } else {
            Toast.makeText(requireContext(), "已取消删除", Toast.LENGTH_SHORT).show()
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
    }

    override fun onResume() {
        super.onResume()
        if (hasAllRequiredPermissions()) viewModel.loadFiles()
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
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("图片"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("视频"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("全部"))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = when (tab.position) {
                    0 -> FileTab.IMAGES
                    1 -> FileTab.VIDEOS
                    else -> FileTab.ALL
                }
                clearSelection()
                renderContent(latestState)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun setupRecyclerView() {
        imageAdapter = ImageGridAdapter(
            onClick = { item -> handleItemClick(item) },
            onLongClick = { item -> enterSelectionWith(item); true }
        )
        fileAdapter = FileListAdapter(
            onClick = { item -> handleItemClick(item) },
            onLongClick = { item -> enterSelectionWith(item); true }
        )
    }

    private fun setupActions() {
        binding.btnRefresh.setOnClickListener {
            clearSelection()
            ensurePermissionsAndLoad()
        }
        binding.btnCancelSelection.setOnClickListener { clearSelection() }
        binding.btnSelectAll.setOnClickListener { toggleSelectAll() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
    }

    private fun observeViewModel() {
        viewModel.state.observe(viewLifecycleOwner) { state ->
            latestState = state
            binding.progressBar.visibility = if (state.loading) View.VISIBLE else View.GONE
            if (state.error != null) {
                Toast.makeText(requireContext(), state.error, Toast.LENGTH_SHORT).show()
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

    private fun renderContent(state: FileSpaceState) {
        val items = currentItems(state)
        binding.tvFileSummary.text = buildSummary(state)

        if (selectedTab == FileTab.IMAGES) {
            if (binding.rvFiles.adapter !== imageAdapter) {
                binding.rvFiles.layoutManager = GridLayoutManager(requireContext(), 3)
                binding.rvFiles.adapter = imageAdapter
            }
            imageAdapter.submitList(items)
        } else {
            if (binding.rvFiles.adapter !== fileAdapter) {
                binding.rvFiles.layoutManager = LinearLayoutManager(requireContext())
                binding.rvFiles.adapter = fileAdapter
            }
            fileAdapter.submitList(items)
        }

        val showEmpty = !state.loading && items.isEmpty()
        binding.emptyView.visibility = if (showEmpty) View.VISIBLE else View.GONE
        binding.rvFiles.visibility = if (showEmpty) View.GONE else View.VISIBLE
        if (showEmpty && hasAllRequiredPermissions()) updateEmptyText()
        renderSelectionState()
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
        binding.normalToolbar.visibility = if (selectionMode) View.GONE else View.VISIBLE
        binding.selectionToolbar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        binding.tvSelectionCount.text = "已选择 ${selectedPaths.size} 项"
        binding.btnDeleteSelected.isEnabled = selectedPaths.isNotEmpty()
        imageAdapter.setSelectionState(selectedPaths, selectionMode)
        fileAdapter.setSelectionState(selectedPaths, selectionMode)
        selectionBackCallback?.isEnabled = selectionMode
    }

    private fun selectedItems(): List<FileItem> {
        val byPath = latestState.allFiles.associateBy { it.path }
        return selectedPaths.mapNotNull { byPath[it] }
    }

    private fun confirmDeleteSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle("删除文件")
            .setMessage("确定删除选中的 ${items.size} 个项目吗？")
            .setPositiveButton("删除") { _, _ -> deleteItems(items) }
            .setNegativeButton("取消", null)
            .show()
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
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), "没有可打开该文件的应用", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(requireContext(), "无法授权系统应用读取该文件", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), "无法打开删除确认", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
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

