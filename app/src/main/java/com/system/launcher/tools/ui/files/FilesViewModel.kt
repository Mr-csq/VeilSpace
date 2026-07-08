package com.system.launcher.tools.ui.files

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.database.Cursor
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class FilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "FilesViewModel"
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "3gpp", "ts", "m2ts")
    }

    sealed class DeleteResult {
        data object Deleted : DeleteResult()
        data class NeedsConfirmation(val intentSender: IntentSender) : DeleteResult()
        data object Failed : DeleteResult()
    }

    data class BatchDeleteResult(
        val deleted: Int,
        val failed: Int,
        val needsConfirmation: NeedsConfirmation?
    ) {
        data class NeedsConfirmation(
            val intentSender: IntentSender,
            val items: List<FileItem>
        )
    }

    private val _state = MutableLiveData(FileSpaceState())
    val state: LiveData<FileSpaceState> = _state

    fun loadFiles() {
        viewModelScope.launch {
            _state.value = _state.value.orEmpty().copy(loading = true, error = null)
            val result = withContext(Dispatchers.IO) { scanProfileStorage() }
            _state.value = result.copy(loading = false)
        }
    }

    fun deleteFile(item: FileItem, onComplete: (DeleteResult) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { deleteFileInternal(item) }
            if (result == DeleteResult.Deleted) loadFiles()
            onComplete(result)
        }
    }

    fun verifyFileDeleted(item: FileItem, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { isFileActuallyDeleted(item) }
            loadFiles()
            onComplete(deleted)
        }
    }

    fun deleteFiles(items: List<FileItem>, onComplete: (BatchDeleteResult) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { deleteFilesInternal(items) }
            loadFiles()
            onComplete(result)
        }
    }

    fun verifyFilesDeleted(items: List<FileItem>, onComplete: (deleted: Int, failed: Int) -> Unit) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { items.count { isFileActuallyDeleted(it) } }
            loadFiles()
            onComplete(deleted, items.size - deleted)
        }
    }

    private fun deleteFilesInternal(items: List<FileItem>): BatchDeleteResult {
        var deleted = 0
        var failed = 0
        val confirmationUris = mutableListOf<Uri>()
        val confirmationItems = mutableListOf<FileItem>()

        items.distinctBy { it.path }.forEach { item ->
            if (tryDirectFileDelete(item)) {
                deleted++
                return@forEach
            }
            val mediaUri = item.contentUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: findMediaUri(item)
            if (mediaUri == null) {
                failed++
            } else {
                confirmationUris.add(mediaUri)
                confirmationItems.add(item)
            }
        }

        if (confirmationUris.isEmpty()) return BatchDeleteResult(deleted, failed, null)

        val confirmation = createBatchDeleteConfirmation(confirmationUris, confirmationItems)
        return BatchDeleteResult(
            deleted = deleted,
            failed = if (confirmation == null) failed + confirmationItems.size else failed,
            needsConfirmation = confirmation
        )
    }

    private fun createBatchDeleteConfirmation(uris: List<Uri>, items: List<FileItem>): BatchDeleteResult.NeedsConfirmation? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
                BatchDeleteResult.NeedsConfirmation(pendingIntent.intentSender, items)
            }.onFailure { error ->
                Log.w(TAG, "MediaStore batch delete request failed", error)
            }.getOrNull()
        } else {
            null
        }
    }

    private fun deleteFileInternal(item: FileItem): DeleteResult {
        if (tryDirectFileDelete(item)) return DeleteResult.Deleted

        val mediaUri = item.contentUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: findMediaUri(item)
            ?: return DeleteResult.Failed

        return deleteMediaUri(mediaUri)
    }

    private fun isFileActuallyDeleted(item: FileItem): Boolean {
        if (File(item.path).exists()) return false
        val mediaUri = item.contentUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: findMediaUri(item)
        return mediaUri == null || !mediaUriExists(mediaUri)
    }

    private fun mediaUriExists(uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { cursor -> cursor.moveToFirst() }
                ?: false
        }.getOrDefault(false)
    }

    private fun tryDirectFileDelete(item: FileItem): Boolean {
        val file = File(item.path)
        if (!file.exists()) return true
        return runCatching { file.delete() && !file.exists() }
            .onFailure { error -> Log.w(TAG, "Direct file delete failed: ${item.path}", error) }
            .getOrDefault(false)
    }

    private fun deleteMediaUri(uri: Uri): DeleteResult {
        val resolver = context.contentResolver
        runCatching { resolver.delete(uri, null, null) }
            .onSuccess { rows -> if (rows > 0) return DeleteResult.Deleted }
            .onFailure { error ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && error is RecoverableSecurityException) {
                    return DeleteResult.NeedsConfirmation(error.userAction.actionIntent.intentSender)
                }
                Log.w(TAG, "MediaStore direct delete failed uri=$uri", error)
            }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val pendingIntent = MediaStore.createDeleteRequest(resolver, listOf(uri))
                DeleteResult.NeedsConfirmation(pendingIntent.intentSender)
            }.onFailure { error ->
                Log.w(TAG, "MediaStore delete request failed uri=$uri", error)
            }.getOrDefault(DeleteResult.Failed)
        } else {
            DeleteResult.Failed
        }
    }

    private fun scanProfileStorage(): FileSpaceState {
        return try {
            val directFiles = scanConfiguredDirectories()
                .mapNotNull { file -> file.toFileItem() }
            val mediaFiles = scanMediaStore()
            val files = (mediaFiles + directFiles)
                .filter { File(it.path).exists() }
                .distinctBy { it.path }
                .sortedByDescending { it.modifiedAt }

            FileSpaceState(
                images = files.filter { it.type == FileType.IMAGE },
                videos = files.filter { it.type == FileType.VIDEO },
                allFiles = files
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning profile storage", e)
            FileSpaceState(error = "读取文件失败：${e.message ?: "未知错误"}")
        }
    }

    private fun scanConfiguredDirectories(): List<File> {
        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        ).distinctBy { it.absolutePath }
        return roots.flatMap { root -> scanDirectory(root) }
    }

    private fun scanMediaStore(): List<FileItem> {
        val images = queryMediaStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, FileType.IMAGE)
        val videos = queryMediaStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, FileType.VIDEO)
        return images + videos
    }

    private fun queryMediaStore(uri: Uri, type: FileType): List<FileItem> {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        if (type == FileType.VIDEO) projection.add(MediaStore.Video.Media.DURATION)

        return runCatching {
            context.contentResolver.query(
                uri,
                projection.toTypedArray(),
                null,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                cursor.toFileItems(type, uri)
            }.orEmpty()
        }.onFailure { error ->
            Log.w(TAG, "MediaStore query failed uri=$uri type=$type", error)
        }.getOrDefault(emptyList())
    }

    private fun Cursor.toFileItems(type: FileType, collectionUri: Uri): List<FileItem> {
        val idIndex = getColumnIndex(MediaStore.MediaColumns._ID)
        val dataIndex = getColumnIndex(MediaStore.MediaColumns.DATA)
        val nameIndex = getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val sizeIndex = getColumnIndex(MediaStore.MediaColumns.SIZE)
        val modifiedIndex = getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        val durationIndex = getColumnIndex(MediaStore.Video.Media.DURATION)
        val result = mutableListOf<FileItem>()

        while (moveToNext()) {
            val path = getStringOrNull(dataIndex) ?: continue
            val file = File(path)
            if (!file.exists() || !file.canRead()) continue
            val id = getLongOrZero(idIndex).takeIf { it > 0L } ?: continue
            val metadata = if (type == FileType.VIDEO) readVideoMetadata(file) else VideoMetadata()
            val mediaDurationMs = if (type == FileType.VIDEO) getLongOrZero(durationIndex) else 0L
            result.add(
                FileItem(
                    path = path,
                    name = getStringOrNull(nameIndex) ?: file.name,
                    sizeBytes = getLongOrZero(sizeIndex).takeIf { it > 0L } ?: file.length(),
                    modifiedAt = getLongOrZero(modifiedIndex).takeIf { it > 0L }?.times(1000L) ?: file.lastModified(),
                    type = type,
                    durationMs = mediaDurationMs.takeIf { it > 0L } ?: metadata.durationMs,
                    thumbnailPath = metadata.thumbnailPath,
                    contentUri = ContentUris.withAppendedId(collectionUri, id).toString()
                )
            )
        }
        return result
    }

    private fun findMediaUri(item: FileItem): Uri? {
        val collections = when (item.type) {
            FileType.IMAGE -> listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, filesCollectionUri())
            FileType.VIDEO -> listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, filesCollectionUri())
            FileType.OTHER -> listOf(filesCollectionUri())
        }
        return collections.firstNotNullOfOrNull { collection -> queryMediaUriByPath(collection, item.path) }
    }

    private fun queryMediaUriByPath(collectionUri: Uri, path: String): Uri? {
        return runCatching {
            context.contentResolver.query(
                collectionUri,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DATA}=?",
                arrayOf(path),
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLongOrZero(cursor.getColumnIndex(MediaStore.MediaColumns._ID))
                if (id > 0L) ContentUris.withAppendedId(collectionUri, id) else null
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to resolve MediaStore uri for $path", error)
        }.getOrNull()
    }

    private fun filesCollectionUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
    }

    private fun Cursor.getStringOrNull(index: Int): String? {
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.getLongOrZero(index: Int): Long {
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }

    private fun scanDirectory(root: File): List<File> {
        if (!root.exists() || !root.canRead()) return emptyList()
        val result = mutableListOf<File>()
        val pending = ArrayDeque<File>()
        pending.add(root)

        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            val children = runCatching { current.listFiles() }.getOrNull().orEmpty()
            children.forEach { child ->
                when {
                    child.isDirectory && child.canRead() -> pending.add(child)
                    child.isFile && child.canRead() -> result.add(child)
                }
            }
        }
        return result
    }

    private fun File.toFileItem(): FileItem? {
        val extension = extension.lowercase(Locale.US)
        val type = when (extension) {
            in IMAGE_EXTENSIONS -> FileType.IMAGE
            in VIDEO_EXTENSIONS -> FileType.VIDEO
            else -> FileType.OTHER
        }

        return when (type) {
            FileType.VIDEO -> {
                val metadata = readVideoMetadata(this)
                FileItem(
                    path = absolutePath,
                    name = name,
                    sizeBytes = length(),
                    modifiedAt = lastModified(),
                    type = type,
                    durationMs = metadata.durationMs,
                    thumbnailPath = metadata.thumbnailPath
                )
            }
            else -> FileItem(
                path = absolutePath,
                name = name,
                sizeBytes = length(),
                modifiedAt = lastModified(),
                type = type
            )
        }
    }

    private fun readVideoMetadata(file: File): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            val thumbnailPath = createVideoThumbnail(file, retriever)
            VideoMetadata(durationMs, thumbnailPath)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read video metadata: ${file.absolutePath}", e)
            VideoMetadata()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun createVideoThumbnail(file: File, retriever: MediaMetadataRetriever): String? {
        val cacheDir = File(context.cacheDir, "video_thumbnails").apply { mkdirs() }
        val thumbFile = File(cacheDir, file.absolutePath.hashCode().toString() + ".jpg")
        if (thumbFile.exists() && thumbFile.lastModified() >= file.lastModified()) return thumbFile.absolutePath

        val bitmap = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.frameAtTime
            ?: return null

        val scaled = scaleBitmap(bitmap, 320)
        FileOutputStream(thumbFile).use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 84, output)
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return thumbFile.absolutePath
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSide: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val largerSide = maxOf(width, height)
        if (largerSide <= maxSide) return bitmap
        val scale = maxSide.toFloat() / largerSide.toFloat()
        return Bitmap.createScaledBitmap(bitmap, (width * scale).toInt(), (height * scale).toInt(), true)
    }

    private fun FileSpaceState?.orEmpty(): FileSpaceState = this ?: FileSpaceState()

    private data class VideoMetadata(
        val durationMs: Long = 0L,
        val thumbnailPath: String? = null
    )
}
