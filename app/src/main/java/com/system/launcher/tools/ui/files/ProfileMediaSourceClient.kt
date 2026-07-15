package com.system.launcher.tools.ui.files

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import com.system.launcher.tools.work.ProfileMediaTransferContract
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

class ProfileMediaSourceClient(
    private val sourceReceiver: ResultReceiver
) {
    data class Verification(val bytes: Long, val sha256: ByteArray)

    class OpenedSource internal constructor(
        val descriptor: ParcelFileDescriptor,
        private val verification: CompletableDeferred<Verification>,
        @Suppress("unused") private val responseReceiver: ResultReceiver
    ) {
        suspend fun awaitVerification(): Verification {
            return withTimeout(VERIFICATION_TIMEOUT_MS) { verification.await() }
        }
    }

    suspend fun open(index: Int): OpenedSource {
        return withTimeout(OPEN_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val verification = CompletableDeferred<Verification>()
                var openedDescriptor: ParcelFileDescriptor? = null
                val response = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        when (resultCode) {
                            ProfileMediaTransferContract.SOURCE_RESULT_OPENED -> {
                                val descriptor = resultData?.parcelFileDescriptor(
                                    ProfileMediaTransferContract.EXTRA_SOURCE_FD
                                )
                                if (descriptor == null) {
                                    fail(IOException("Source descriptor is missing"))
                                } else if (continuation.isActive) {
                                    openedDescriptor = descriptor
                                    continuation.resume(OpenedSource(descriptor, verification, this))
                                } else {
                                    descriptor.close()
                                }
                            }
                            ProfileMediaTransferContract.SOURCE_RESULT_COMPLETE -> {
                                val bytes = resultData?.getLong(
                                    ProfileMediaTransferContract.EXTRA_SOURCE_BYTES,
                                    -1L
                                ) ?: -1L
                                val digest = resultData?.getByteArray(
                                    ProfileMediaTransferContract.EXTRA_SOURCE_SHA256
                                )
                                if (bytes < 0L || digest == null) {
                                    verification.completeExceptionally(IOException("Source verification is missing"))
                                } else {
                                    verification.complete(Verification(bytes, digest))
                                }
                            }
                            ProfileMediaTransferContract.SOURCE_RESULT_FAILED -> {
                                fail(IOException("Work-profile source stream failed"))
                            }
                        }
                    }

                    private fun fail(error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                        verification.completeExceptionally(error)
                    }
                }
                continuation.invokeOnCancellation {
                    runCatching { openedDescriptor?.close() }
                    verification.cancel()
                }
                runCatching {
                    sourceReceiver.send(
                        ProfileMediaTransferContract.SOURCE_REQUEST_OPEN,
                        Bundle().apply {
                            putInt(ProfileMediaTransferContract.EXTRA_SOURCE_INDEX, index)
                            putParcelable(ProfileMediaTransferContract.EXTRA_SOURCE_RESPONSE, response)
                        }
                    )
                }.onFailure { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                    verification.completeExceptionally(error)
                }
            }
        }
    }

    fun closeSession() {
        runCatching {
            sourceReceiver.send(ProfileMediaTransferContract.SOURCE_REQUEST_CLOSE, Bundle.EMPTY)
        }
    }

    companion object {
        private const val OPEN_TIMEOUT_MS = 15_000L
        private const val VERIFICATION_TIMEOUT_MS = 30_000L

        @Suppress("DEPRECATION")
        private fun Bundle.parcelFileDescriptor(name: String): ParcelFileDescriptor? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelable(name, ParcelFileDescriptor::class.java)
            } else {
                getParcelable(name)
            }
        }
    }
}
