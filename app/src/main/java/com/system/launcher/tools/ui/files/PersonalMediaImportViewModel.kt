package com.system.launcher.tools.ui.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.system.launcher.tools.work.ProfileMediaTransferContract
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class PersonalMediaImportState(
    val operation: ProfileMediaTransferContract.Operation = ProfileMediaTransferContract.Operation.COPY,
    val running: Boolean = false,
    val completed: Boolean = false,
    val unsupported: Boolean = false,
    val total: Int = 0,
    val processed: Int = 0,
    val copied: Int = 0,
    val failed: Int = 0,
    val currentName: String? = null,
    val failedNames: List<String> = emptyList(),
    val successfulIndices: List<Int> = emptyList()
)

@HiltViewModel
class PersonalMediaImportViewModel @Inject constructor(
    @ApplicationContext context: Context
) : ViewModel() {
    private val resolver = context.contentResolver

    companion object {
        private const val TAG = "PersonalMediaImport"
        private const val COPY_BUFFER_SIZE = 256 * 1024
    }

    private val _state = MutableLiveData(PersonalMediaImportState())
    val state: LiveData<PersonalMediaImportState> = _state
    private var started = false

    fun startImport(
        sources: List<ProfileMediaTransferContract.SourceDescriptor>,
        sourceReceiver: ResultReceiver,
        operation: ProfileMediaTransferContract.Operation
    ) {
        if (started) return
        started = true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ProfileMediaSourceClient(sourceReceiver).closeSession()
            _state.value = PersonalMediaImportState(
                operation = operation,
                completed = true,
                unsupported = true,
                total = sources.size,
                processed = sources.size,
                failed = sources.size
            )
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            var copied = 0
            val failedNames = mutableListOf<String>()
            val successfulIndices = mutableListOf<Int>()
            val sourceClient = ProfileMediaSourceClient(sourceReceiver)
            _state.postValue(PersonalMediaImportState(operation = operation, running = true, total = sources.size))

            try {
                sources.forEachIndexed { index, source ->
                    val fallbackName = "media_${index + 1}"
                    val metadata = source.toMetadata(fallbackName)
                    _state.postValue(
                        PersonalMediaImportState(
                            operation = operation,
                            running = true,
                            total = sources.size,
                            processed = index,
                            copied = copied,
                            failed = failedNames.size,
                            currentName = metadata?.displayName ?: fallbackName,
                            failedNames = failedNames.toList(),
                            successfulIndices = successfulIndices.toList()
                        )
                    )

                    val copiedSuccessfully = metadata != null && runCatching {
                        importMedia(sourceClient, index, metadata)
                    }.getOrDefault(false)
                    if (copiedSuccessfully) {
                        copied++
                        successfulIndices += index
                    } else {
                        failedNames += metadata?.displayName ?: fallbackName
                    }

                    _state.postValue(
                        PersonalMediaImportState(
                            operation = operation,
                            running = index < sources.lastIndex,
                            total = sources.size,
                            processed = index + 1,
                            copied = copied,
                            failed = failedNames.size,
                            currentName = if (index < sources.lastIndex) metadata?.displayName else null,
                            failedNames = failedNames.toList(),
                            successfulIndices = successfulIndices.toList()
                        )
                    )
                }
            } finally {
                sourceClient.closeSession()
            }

            _state.postValue(
                PersonalMediaImportState(
                    operation = operation,
                    completed = true,
                    total = sources.size,
                    processed = sources.size,
                    copied = copied,
                    failed = failedNames.size,
                    failedNames = failedNames.toList(),
                    successfulIndices = successfulIndices.toList()
                )
            )
        }
    }

    private fun ProfileMediaTransferContract.SourceDescriptor.toMetadata(
        fallbackName: String
    ): SourceMetadata? {
        val normalizedName = MediaImportNaming.sanitizeDisplayName(displayName, fallbackName)
        if (mimeType.isBlank()) return null
        return SourceMetadata(normalizedName, mimeType, expectedSize.coerceAtLeast(0L), kind)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun importMedia(
        sourceClient: ProfileMediaSourceClient,
        sourceIndex: Int,
        metadata: SourceMetadata
    ): Boolean {
        val relativePath = when (metadata.kind) {
            ProfileMediaTransferContract.MediaKind.IMAGE -> "${Environment.DIRECTORY_PICTURES}/VeilSpace/"
            ProfileMediaTransferContract.MediaKind.VIDEO -> "${Environment.DIRECTORY_MOVIES}/VeilSpace/"
        }
        val collection = when (metadata.kind) {
            ProfileMediaTransferContract.MediaKind.IMAGE ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            ProfileMediaTransferContract.MediaKind.VIDEO ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val destinationName = findAvailableDisplayName(collection, relativePath, metadata.displayName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, destinationName)
            put(MediaStore.MediaColumns.MIME_TYPE, metadata.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destinationUri = resolver.insert(collection, values) ?: return false

        return try {
            val openedSource = sourceClient.open(sourceIndex)
            val copiedDigest = MessageDigest.getInstance("SHA-256")
            val copiedBytes = ParcelFileDescriptor.AutoCloseInputStream(openedSource.descriptor).use { input ->
                resolver.openOutputStream(destinationUri, "w")?.use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copiedDigest.update(buffer, 0, read)
                        total += read
                    }
                    output.flush()
                    total
                } ?: throw IOException("Unable to open destination")
            }

            val sourceVerification = openedSource.awaitVerification()
            if (!ProfileMediaCopyVerifier.isVerified(
                    expectedSize = metadata.expectedSize,
                    copiedBytes = copiedBytes,
                    sourceBytes = sourceVerification.bytes,
                    copiedSha256 = copiedDigest.digest(),
                    sourceSha256 = sourceVerification.sha256
                )
            ) {
                throw IOException("Copied media verification failed")
            }
            val finalized = resolver.update(
                destinationUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            if (finalized <= 0) throw IOException("Unable to publish destination")
            Log.i(TAG, "Imported media index=$sourceIndex name=$destinationName bytes=$copiedBytes")
            true
        } catch (error: Exception) {
            Log.w(TAG, "Unable to import media name=${metadata.displayName}", error)
            runCatching { resolver.delete(destinationUri, null, null) }
            false
        }
    }

    private fun findAvailableDisplayName(collection: Uri, relativePath: String, requestedName: String): String {
        repeat(1_000) { index ->
            val candidate = MediaImportNaming.numberedDisplayName(requestedName, index)
            if (!displayNameExists(collection, relativePath, candidate)) return candidate
        }
        return MediaImportNaming.numberedDisplayName(
            requestedName,
            (System.currentTimeMillis() % 100_000).toInt() + 1
        )
    }

    private fun displayNameExists(collection: Uri, relativePath: String, displayName: String): Boolean {
        return runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(relativePath, displayName),
                null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    private data class SourceMetadata(
        val displayName: String,
        val mimeType: String,
        val expectedSize: Long,
        val kind: ProfileMediaTransferContract.MediaKind
    )
}
