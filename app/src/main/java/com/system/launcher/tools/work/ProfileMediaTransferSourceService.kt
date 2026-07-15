package com.system.launcher.tools.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.util.Log
import com.system.launcher.tools.R

class ProfileMediaTransferSourceService : Service() {
    private val sessions = mutableMapOf<String, ProfileMediaTransferSourceSession>()
    private var destroying = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        Log.i(TAG, "Foreground media source service started pid=${android.os.Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_PREPARE_TRANSFER) return START_NOT_STICKY
        val transferId = intent.getStringExtra(ProfileMediaTransferContract.EXTRA_TRANSFER_ID)
            ?.takeIf { it.isNotBlank() }
        val uris = intent.uriListExtra(EXTRA_SOURCE_URIS)
        val callback = intent.resultReceiverExtra(EXTRA_PREPARE_CALLBACK)
        if (transferId == null || uris.isEmpty() || callback == null) {
            callback?.send(PREPARE_FAILED, Bundle.EMPTY)
            stopIfIdle()
            return START_NOT_STICKY
        }

        sessions.remove(transferId)?.close("replaced")
        val session = ProfileMediaTransferSourceSession.create(
            context = this,
            transferId = transferId,
            uris = uris,
            onClosed = { removeSession(transferId) }
        )
        if (session == null) {
            callback.send(PREPARE_FAILED, Bundle.EMPTY)
            Log.e(TAG, "Unable to prepare media source session id=$transferId")
            stopIfIdle()
            return START_NOT_STICKY
        }
        sessions[transferId] = session
        callback.send(
            PREPARE_COMPLETE,
            ProfileMediaTransferContract.createPreparedSourceBundle(
                sources = session.descriptors,
                sourceReceiver = session.receiver
            )
        )
        Log.i(TAG, "Prepared media source session id=$transferId count=${uris.size}")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroying = true
        sessions.values.toList().forEach { it.close("service_destroyed") }
        sessions.clear()
        Log.i(TAG, "Foreground media source service stopped")
        super.onDestroy()
    }

    private fun removeSession(transferId: String) {
        sessions.remove(transferId)
        if (!destroying) stopIfIdle()
    }

    private fun stopIfIdle() {
        if (sessions.isEmpty()) stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.media_transfer_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.media_transfer_channel_description)
                setShowBadge(false)
            }
        )
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_copy_to_personal_24)
            .setContentTitle(getString(R.string.media_transfer_service_title))
            .setContentText(getString(R.string.media_transfer_service_summary))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val PREPARE_COMPLETE = 1
        const val PREPARE_FAILED = 2

        private const val TAG = "ProfileMediaSource"
        private const val ACTION_PREPARE_TRANSFER =
            "com.system.launcher.tools.action.PREPARE_MEDIA_TRANSFER_SOURCE"
        private const val EXTRA_SOURCE_URIS = "com.system.launcher.tools.extra.SOURCE_URIS"
        private const val EXTRA_PREPARE_CALLBACK = "com.system.launcher.tools.extra.PREPARE_CALLBACK"
        private const val CHANNEL_ID = "profile_media_transfer"
        private const val NOTIFICATION_ID = 4102

        fun start(
            context: Context,
            transferId: String,
            uris: List<Uri>,
            callback: ResultReceiver
        ): Boolean {
            return runCatching {
                val intent = Intent(context, ProfileMediaTransferSourceService::class.java).apply {
                    action = ACTION_PREPARE_TRANSFER
                    putExtra(ProfileMediaTransferContract.EXTRA_TRANSFER_ID, transferId)
                    putParcelableArrayListExtra(EXTRA_SOURCE_URIS, ArrayList(uris))
                    putExtra(EXTRA_PREPARE_CALLBACK, callback)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            }.onFailure { error ->
                Log.e(TAG, "Unable to start media source service id=$transferId", error)
            }.getOrDefault(false)
        }

        @Suppress("DEPRECATION")
        private fun Intent.resultReceiverExtra(name: String): ResultReceiver? {
            setExtrasClassLoader(ProfileMediaTransferSourceService::class.java.classLoader)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(name, ResultReceiver::class.java)
            } else {
                getParcelableExtra(name)
            }
        }

        @Suppress("DEPRECATION")
        private fun Intent.uriListExtra(name: String): List<Uri> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableArrayListExtra(name, Uri::class.java).orEmpty()
            } else {
                getParcelableArrayListExtra<Uri>(name).orEmpty()
            }
        }
    }
}
