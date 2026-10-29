package com.atelierapps.vault.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.atelierapps.vault.session.AppDisguise

/**
 * Keeps the process alive and at foreground priority while a long transfer
 * runs, so deep Doze or memory pressure can't stop a backup or restore
 * halfway. It does no work itself: the transfer stays in its app-scoped
 * coroutine, and this only tells Android the process is doing something the
 * user asked for. Started and stopped by [TransferWake].
 *
 * Android only grants that with a notification, and the owner accepted one on
 * these terms: silent, low importance, titled with the disguise's name and
 * worded like any file app ("Copying files 120 / 482"), gone when the run
 * ends. The app never asks for notification permission, so on Android 13+ it
 * shows only if notifications were allowed in system settings; otherwise it
 * sits in the quick-settings "active apps" list. The protection works either
 * way.
 *
 * START_NOT_STICKY: if the process dies anyway the run is gone, and a revived
 * service with nothing to keep alive would only be a stray notification.
 */
class TransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: DEFAULT_LABEL
        runCatching {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification(this, label, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }.onFailure {
            // Not allowed right now (started from the background). The wake
            // lock still runs the transfer; this just can't shield it.
            Log.w(TAG, "foreground start refused", it)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // Android 15 caps dataSync services at six hours a day. Past that it asks
    // us to stop; the transfer carries on under its wake lock regardless.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        private const val TAG = "TransferService"
        private const val CHANNEL = "transfers"
        private const val NOTIF_ID = 4712
        private const val EXTRA_LABEL = "label"
        private const val DEFAULT_LABEL = "Copying files"

        @Volatile private var label = DEFAULT_LABEL
        @Volatile private var lastUpdate = 0L

        fun start(context: Context, text: String) {
            label = text
            runCatching {
                context.startForegroundService(
                    Intent(context, TransferService::class.java).putExtra(EXTRA_LABEL, text),
                )
            }.onFailure { Log.w(TAG, "couldn't start", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferService::class.java)) }
        }

        /** Throttled to once a second: a notification per file is wasted work. */
        fun progress(context: Context, done: Int, total: Int) {
            val now = SystemClock.elapsedRealtime()
            if (done < total && now - lastUpdate < 1_000L) return
            lastUpdate = now
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, notification(context, label, done to total))
            }
        }

        private fun notification(context: Context, text: String, count: Pair<Int, Int>?): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            // The channel's name is visible in the app's notification settings,
            // so it says what a file app would.
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transfers", NotificationManager.IMPORTANCE_LOW))
            val body = if (count == null) text else text + " " + count.first + " / " + count.second
            return NotificationCompat.Builder(context, CHANNEL)
                .setContentTitle(AppDisguise.currentLabel(context))
                .setContentText(body)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .apply { if (count != null && count.second > 0) setProgress(count.second, count.first, false) }
                .build()
        }
    }
}
