package com.system.launcher.tools.work

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver

object ProfileMediaTransferContract {
    const val ACTION_IMPORT_MEDIA_TO_PERSONAL =
        "com.system.launcher.tools.action.IMPORT_MEDIA_TO_PERSONAL"
    const val ACTION_MEDIA_TRANSFER_RESULT =
        "com.system.launcher.tools.action.MEDIA_TRANSFER_RESULT"
    const val MAX_ITEMS_PER_TRANSFER = 200

    const val EXTRA_TRANSFER_ID = "com.system.launcher.tools.extra.TRANSFER_ID"
    const val EXTRA_OPERATION = "com.system.launcher.tools.extra.TRANSFER_OPERATION"
    const val EXTRA_RESULT_CALLBACK = "com.system.launcher.tools.extra.TRANSFER_RESULT_CALLBACK"
    const val EXTRA_SOURCE_RECEIVER = "com.system.launcher.tools.extra.SOURCE_RECEIVER"
    const val EXTRA_SOURCE_NAMES = "com.system.launcher.tools.extra.SOURCE_NAMES"
    const val EXTRA_SOURCE_MIME_TYPES = "com.system.launcher.tools.extra.SOURCE_MIME_TYPES"
    const val EXTRA_SOURCE_SIZES = "com.system.launcher.tools.extra.SOURCE_SIZES"
    const val EXTRA_SOURCE_KINDS = "com.system.launcher.tools.extra.SOURCE_KINDS"
    const val EXTRA_SUCCESSFUL_INDICES = "com.system.launcher.tools.extra.SUCCESSFUL_INDICES"
    const val EXTRA_FAILED_COUNT = "com.system.launcher.tools.extra.FAILED_COUNT"
    const val EXTRA_SOURCE_INDEX = "com.system.launcher.tools.extra.SOURCE_INDEX"
    const val EXTRA_SOURCE_RESPONSE = "com.system.launcher.tools.extra.SOURCE_RESPONSE"
    const val EXTRA_SOURCE_FD = "com.system.launcher.tools.extra.SOURCE_FD"
    const val EXTRA_SOURCE_BYTES = "com.system.launcher.tools.extra.SOURCE_BYTES"
    const val EXTRA_SOURCE_SHA256 = "com.system.launcher.tools.extra.SOURCE_SHA256"

    const val SOURCE_REQUEST_OPEN = 1
    const val SOURCE_REQUEST_CLOSE = 2
    const val SOURCE_RESULT_OPENED = 10
    const val SOURCE_RESULT_COMPLETE = 11
    const val SOURCE_RESULT_FAILED = 12

    enum class Operation {
        COPY,
        MOVE
    }

    enum class MediaKind {
        IMAGE,
        VIDEO
    }

    data class SourceDescriptor(
        val displayName: String,
        val mimeType: String,
        val expectedSize: Long,
        val kind: MediaKind
    )

    data class PreparedSource(
        val sources: List<SourceDescriptor>,
        val sourceReceiver: ResultReceiver
    )

    data class ImportRequest(
        val transferId: String,
        val operation: Operation,
        val sources: List<SourceDescriptor>,
        val sourceReceiver: ResultReceiver,
        val resultCallback: PendingIntent?
    )

    fun createPreparedSourceBundle(
        sources: List<SourceDescriptor>,
        sourceReceiver: ResultReceiver
    ): Bundle {
        require(sources.isNotEmpty()) { "At least one media source is required" }
        require(sources.size <= MAX_ITEMS_PER_TRANSFER) {
            "A transfer can contain at most $MAX_ITEMS_PER_TRANSFER items"
        }
        return Bundle().apply {
            putExtraSources(sources)
            putParcelable(EXTRA_SOURCE_RECEIVER, sourceReceiver)
        }
    }

    fun readPreparedSource(bundle: Bundle?): PreparedSource? {
        bundle?.classLoader = ProfileMediaTransferContract::class.java.classLoader
        val sourceReceiver = bundle?.resultReceiver(EXTRA_SOURCE_RECEIVER) ?: return null
        val sources = bundle.readSources() ?: return null
        return PreparedSource(sources, sourceReceiver)
    }

    fun createImportIntent(
        transferId: String,
        operation: Operation,
        sources: List<SourceDescriptor>,
        sourceReceiver: ResultReceiver,
        resultCallback: PendingIntent?
    ): Intent {
        require(transferId.isNotBlank()) { "Transfer ID is required" }
        require(sources.isNotEmpty()) { "At least one media source is required" }
        require(sources.size <= MAX_ITEMS_PER_TRANSFER) {
            "A transfer can contain at most $MAX_ITEMS_PER_TRANSFER items"
        }

        return Intent(ACTION_IMPORT_MEDIA_TO_PERSONAL).apply {
            setPackage("com.system.launcher.tools")
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(EXTRA_TRANSFER_ID, transferId)
            putExtra(EXTRA_OPERATION, operation.name)
            putExtra(EXTRA_SOURCE_RECEIVER, sourceReceiver)
            putExtras(Bundle().apply { putExtraSources(sources) })
            resultCallback?.let { putExtra(EXTRA_RESULT_CALLBACK, it) }
        }
    }

    fun readImportRequest(intent: Intent): ImportRequest? {
        if (intent.action != ACTION_IMPORT_MEDIA_TO_PERSONAL) return null
        intent.setExtrasClassLoader(ProfileMediaTransferContract::class.java.classLoader)
        val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID)?.takeIf { it.isNotBlank() } ?: return null
        val operation = intent.getStringExtra(EXTRA_OPERATION)
            ?.let { runCatching { Operation.valueOf(it) }.getOrNull() }
            ?: return null
        val sourceReceiver = intent.resultReceiverExtra(EXTRA_SOURCE_RECEIVER) ?: return null
        val sources = intent.extras.readSources() ?: return null
        return ImportRequest(
            transferId = transferId,
            operation = operation,
            sources = sources,
            sourceReceiver = sourceReceiver,
            resultCallback = intent.pendingIntentExtra(EXTRA_RESULT_CALLBACK)
        )
    }

    fun createResultIntent(successfulIndices: List<Int>, failedCount: Int): Intent {
        return Intent().apply {
            putExtra(EXTRA_SUCCESSFUL_INDICES, successfulIndices.toIntArray())
            putExtra(EXTRA_FAILED_COUNT, failedCount.coerceAtLeast(0))
        }
    }

    private fun Bundle.putExtraSources(sources: List<SourceDescriptor>) {
        putStringArrayList(EXTRA_SOURCE_NAMES, ArrayList(sources.map { it.displayName }))
        putStringArrayList(EXTRA_SOURCE_MIME_TYPES, ArrayList(sources.map { it.mimeType }))
        putLongArray(EXTRA_SOURCE_SIZES, sources.map { it.expectedSize }.toLongArray())
        putStringArrayList(EXTRA_SOURCE_KINDS, ArrayList(sources.map { it.kind.name }))
    }

    private fun Bundle?.readSources(): List<SourceDescriptor>? {
        val source = this ?: return null
        val names = source.getStringArrayList(EXTRA_SOURCE_NAMES) ?: return null
        val mimeTypes = source.getStringArrayList(EXTRA_SOURCE_MIME_TYPES) ?: return null
        val sizes = source.getLongArray(EXTRA_SOURCE_SIZES) ?: return null
        val kinds = source.getStringArrayList(EXTRA_SOURCE_KINDS) ?: return null
        if (names.isEmpty() || names.size > MAX_ITEMS_PER_TRANSFER) return null
        if (mimeTypes.size != names.size || sizes.size != names.size || kinds.size != names.size) return null
        val sources = names.indices.mapNotNull { index ->
            val name = names[index].takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val mimeType = mimeTypes[index].takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val kind = runCatching { MediaKind.valueOf(kinds[index]) }.getOrNull()
                ?: return@mapNotNull null
            SourceDescriptor(name, mimeType, sizes[index].coerceAtLeast(0L), kind)
        }
        return sources.takeIf { it.size == names.size }
    }

    @Suppress("DEPRECATION")
    private fun Intent.pendingIntentExtra(name: String): PendingIntent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, PendingIntent::class.java)
        } else {
            getParcelableExtra(name)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.resultReceiverExtra(name: String): ResultReceiver? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, ResultReceiver::class.java)
        } else {
            getParcelableExtra(name)
        }
    }

        @Suppress("DEPRECATION")
    private fun Bundle.resultReceiver(name: String): ResultReceiver? {
        classLoader = ProfileMediaTransferContract::class.java.classLoader
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(name, ResultReceiver::class.java)
        } else {
            getParcelable(name)
        }
    }
}
