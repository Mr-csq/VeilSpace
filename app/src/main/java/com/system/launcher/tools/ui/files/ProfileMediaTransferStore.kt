package com.system.launcher.tools.ui.files

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object ProfileMediaTransferStore {
    private const val TAG = "ProfileMediaTransfer"
    private const val PREFS_NAME = "profile_media_transfer"
    private const val KEY_PENDING_MOVE = "pending_move"
    private const val MAX_PENDING_AGE_MS = 24 * 60 * 60 * 1000L

    data class CompletedMove(
        val transferId: String,
        val copiedItems: List<FileItem>,
        val copyFailed: Int
    )

    fun savePendingMove(context: Context, transferId: String, items: List<FileItem>) {
        val payload = JSONObject().apply {
            put("transferId", transferId)
            put("createdAt", System.currentTimeMillis())
            put("completed", false)
            put("items", JSONArray().apply {
                items.forEach { item -> put(item.toJson()) }
            })
        }
        prefs(context).edit().putString(KEY_PENDING_MOVE, payload.toString()).apply()
        Log.i(TAG, "Saved pending move transferId=$transferId count=${items.size}")
    }

    fun markMoveCopied(
        context: Context,
        transferId: String,
        successfulIndices: IntArray,
        reportedFailedCount: Int
    ): Boolean {
        val payload = readPayload(context) ?: return false
        if (payload.optString("transferId") != transferId) {
            Log.w(TAG, "Ignoring stale move result transferId=$transferId")
            return false
        }
        val itemCount = payload.optJSONArray("items")?.length() ?: 0
        val normalizedIndices = successfulIndices
            .asSequence()
            .filter { it in 0 until itemCount }
            .distinct()
            .sorted()
            .toList()
        payload.put("completed", true)
        payload.put("successfulIndices", JSONArray(normalizedIndices))
        payload.put("copyFailed", maxOf(reportedFailedCount, itemCount - normalizedIndices.size))
        prefs(context).edit().putString(KEY_PENDING_MOVE, payload.toString()).apply()
        Log.i(
            TAG,
            "Recorded move copy result transferId=$transferId copied=${normalizedIndices.size} failed=${itemCount - normalizedIndices.size}"
        )
        return true
    }

    fun getCompletedMove(context: Context): CompletedMove? {
        val payload = readPayload(context) ?: return null
        val createdAt = payload.optLong("createdAt", 0L)
        if (createdAt <= 0L || System.currentTimeMillis() - createdAt > MAX_PENDING_AGE_MS) {
            clear(context)
            Log.w(TAG, "Discarded expired pending move")
            return null
        }
        if (!payload.optBoolean("completed", false)) return null

        val itemsJson = payload.optJSONArray("items") ?: return null
        val successfulJson = payload.optJSONArray("successfulIndices") ?: JSONArray()
        val successfulIndices = buildSet {
            repeat(successfulJson.length()) { index -> add(successfulJson.optInt(index, -1)) }
        }
        val copiedItems = buildList {
            repeat(itemsJson.length()) { index ->
                if (index in successfulIndices) itemsJson.optJSONObject(index)?.toFileItem()?.let(::add)
            }
        }
        return CompletedMove(
            transferId = payload.optString("transferId"),
            copiedItems = copiedItems,
            copyFailed = payload.optInt("copyFailed", itemsJson.length() - copiedItems.size).coerceAtLeast(0)
        )
    }

    fun clearPendingMove(context: Context, transferId: String) {
        val payload = readPayload(context) ?: return
        if (payload.optString("transferId") == transferId) clear(context)
    }

    private fun readPayload(context: Context): JSONObject? {
        val raw = prefs(context).getString(KEY_PENDING_MOVE, null) ?: return null
        return runCatching { JSONObject(raw) }
            .onFailure {
                Log.w(TAG, "Unable to read pending move", it)
                clear(context)
            }
            .getOrNull()
    }

    private fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PENDING_MOVE).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun FileItem.toJson(): JSONObject = JSONObject().apply {
        put("path", path)
        put("name", name)
        put("sizeBytes", sizeBytes)
        put("modifiedAt", modifiedAt)
        put("type", type.name)
        put("contentUri", contentUri ?: JSONObject.NULL)
    }

    private fun JSONObject.toFileItem(): FileItem? {
        val path = optString("path").takeIf { it.isNotBlank() } ?: return null
        val type = optString("type")
            .let { runCatching { FileType.valueOf(it) }.getOrNull() }
            ?: return null
        return FileItem(
            path = path,
            name = optString("name").ifBlank { path.substringAfterLast('/') },
            sizeBytes = optLong("sizeBytes", 0L),
            modifiedAt = optLong("modifiedAt", 0L),
            type = type,
            contentUri = optString("contentUri").takeIf { it.isNotBlank() && it != "null" }
        )
    }
}
