package com.system.launcher.tools.ui.files

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.system.launcher.tools.work.ProfileMediaTransferContract

class ProfileMediaTransferResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ProfileMediaTransferContract.ACTION_MEDIA_TRANSFER_RESULT) return
        val transferId = intent.getStringExtra(ProfileMediaTransferContract.EXTRA_TRANSFER_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return
        val successfulIndices = intent.getIntArrayExtra(
            ProfileMediaTransferContract.EXTRA_SUCCESSFUL_INDICES
        ) ?: intArrayOf()
        val failedCount = intent.getIntExtra(ProfileMediaTransferContract.EXTRA_FAILED_COUNT, 0)
        val recorded = ProfileMediaTransferStore.markMoveCopied(
            context,
            transferId,
            successfulIndices,
            failedCount
        )
        Log.i(TAG, "Received media transfer result transferId=$transferId recorded=$recorded")
    }

    companion object {
        private const val TAG = "ProfileMediaTransfer"

        fun createCallback(context: Context, transferId: String): PendingIntent {
            val intent = Intent(context, ProfileMediaTransferResultReceiver::class.java).apply {
                action = ProfileMediaTransferContract.ACTION_MEDIA_TRANSFER_RESULT
                putExtra(ProfileMediaTransferContract.EXTRA_TRANSFER_ID, transferId)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            return PendingIntent.getBroadcast(context, transferId.hashCode(), intent, flags)
        }
    }
}
