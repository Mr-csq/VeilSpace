package com.system.launcher.tools.work

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ProfileMediaTransferSourceSession private constructor(
    private val resolver: ContentResolver,
    val transferId: String,
    private val uris: List<Uri>,
    val descriptors: List<ProfileMediaTransferContract.SourceDescriptor>,
    private val onClosed: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val expiration = Runnable { close("expired") }

    val receiver: ResultReceiver = object : ResultReceiver(mainHandler) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            when (resultCode) {
                ProfileMediaTransferContract.SOURCE_REQUEST_OPEN -> openRequestedSource(resultData)
                ProfileMediaTransferContract.SOURCE_REQUEST_CLOSE -> close("remote_complete")
            }
        }
    }

    init {
        mainHandler.postDelayed(expiration, SESSION_TIMEOUT_MS)
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(expiration)
        scope.cancel()
        onClosed()
        Log.i(TAG, "Closed media source session id=$transferId reason=$reason")
    }

    private fun openRequestedSource(data: Bundle?) {
        val index = data?.getInt(ProfileMediaTransferContract.EXTRA_SOURCE_INDEX, -1) ?: -1
        val response = data?.resultReceiver(ProfileMediaTransferContract.EXTRA_SOURCE_RESPONSE)
        if (closed.get() || index !in uris.indices || response == null) {
            response?.send(ProfileMediaTransferContract.SOURCE_RESULT_FAILED, Bundle.EMPTY)
            Log.w(TAG, "Rejected media source request id=$transferId index=$index closed=${closed.get()}")
            return
        }
        scope.launch { streamSource(index, response) }
    }

    private fun streamSource(index: Int, response: ResultReceiver) {
        val input = runCatching { resolver.openInputStream(uris[index]) }.getOrNull()
        if (input == null) {
            response.send(ProfileMediaTransferContract.SOURCE_RESULT_FAILED, Bundle.EMPTY)
            Log.w(TAG, "Unable to open media source id=$transferId index=$index")
            return
        }

        var writeSide: ParcelFileDescriptor? = null
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val activeWriteSide = pipe[1]
            writeSide = activeWriteSide
            readSide.use { descriptor ->
                response.send(
                    ProfileMediaTransferContract.SOURCE_RESULT_OPENED,
                    Bundle().apply {
                        putParcelable(ProfileMediaTransferContract.EXTRA_SOURCE_FD, descriptor)
                    }
                )
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var copiedBytes = 0L
            input.use { source ->
                ParcelFileDescriptor.AutoCloseOutputStream(activeWriteSide).use { output ->
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copiedBytes += read
                    }
                    output.flush()
                }
            }
            writeSide = null
            response.send(
                ProfileMediaTransferContract.SOURCE_RESULT_COMPLETE,
                Bundle().apply {
                    putLong(ProfileMediaTransferContract.EXTRA_SOURCE_BYTES, copiedBytes)
                    putByteArray(ProfileMediaTransferContract.EXTRA_SOURCE_SHA256, digest.digest())
                }
            )
            Log.i(TAG, "Streamed media source id=$transferId index=$index bytes=$copiedBytes")
        } catch (error: Exception) {
            runCatching { writeSide?.close() }
            response.send(ProfileMediaTransferContract.SOURCE_RESULT_FAILED, Bundle.EMPTY)
            Log.w(TAG, "Unable to stream media source id=$transferId index=$index", error)
        } finally {
            runCatching { input.close() }
            runCatching { writeSide?.close() }
        }
    }

    companion object {
        private const val TAG = "ProfileMediaSource"
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val SESSION_TIMEOUT_MS = 2 * 60 * 60 * 1000L
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "3gpp", "ts", "m2ts")

        fun create(
            context: Context,
            transferId: String,
            uris: List<Uri>,
            onClosed: () -> Unit
        ): ProfileMediaTransferSourceSession? {
            val resolver = context.contentResolver
            val descriptors = uris.mapIndexed { index, uri ->
                readDescriptor(resolver, uri, "media_${index + 1}") ?: return null
            }
            return ProfileMediaTransferSourceSession(
                resolver = resolver,
                transferId = transferId,
                uris = uris,
                descriptors = descriptors,
                onClosed = onClosed
            )
        }

        private fun readDescriptor(
            resolver: ContentResolver,
            uri: Uri,
            fallbackName: String
        ): ProfileMediaTransferContract.SourceDescriptor? {
            var rawName: String? = null
            var expectedSize = 0L
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) rawName = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) expectedSize = cursor.getLong(sizeIndex)
                    }
                }
            }
            if (expectedSize <= 0L) {
                expectedSize = runCatching {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                }.getOrNull()?.takeIf { it > 0L } ?: 0L
            }

            val displayName = rawName?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
                ?: fallbackName
            val extension = displayName.substringAfterLast('.', "").lowercase(Locale.US)
            val declaredMime = runCatching { resolver.getType(uri) }.getOrNull()?.lowercase(Locale.US)
            val extensionMime = extension.takeIf { it.isNotBlank() }
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                ?.lowercase(Locale.US)
            val mimeType = declaredMime?.takeUnless { it == "application/octet-stream" } ?: extensionMime
            val kind = when {
                mimeType?.startsWith("image/") == true || extension in IMAGE_EXTENSIONS ->
                    ProfileMediaTransferContract.MediaKind.IMAGE
                mimeType?.startsWith("video/") == true || extension in VIDEO_EXTENSIONS ->
                    ProfileMediaTransferContract.MediaKind.VIDEO
                else -> return null
            }
            val normalizedMime = mimeType ?: when (kind) {
                ProfileMediaTransferContract.MediaKind.IMAGE -> "image/jpeg"
                ProfileMediaTransferContract.MediaKind.VIDEO -> "video/mp4"
            }
            return ProfileMediaTransferContract.SourceDescriptor(
                displayName = displayName,
                mimeType = normalizedMime,
                expectedSize = expectedSize.coerceAtLeast(0L),
                kind = kind
            )
        }

        @Suppress("DEPRECATION")
        private fun Bundle.resultReceiver(name: String): ResultReceiver? {
            classLoader = ProfileMediaTransferSourceSession::class.java.classLoader
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelable(name, ResultReceiver::class.java)
            } else {
                getParcelable(name)
            }
        }
    }
}
