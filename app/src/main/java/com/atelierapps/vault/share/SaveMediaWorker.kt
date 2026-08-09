package com.atelierapps.vault.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.atelierapps.vault.data.entity.SourceType
import com.atelierapps.vault.media.MediaSaver
import com.atelierapps.vault.media.SaveRequest
import com.atelierapps.vault.media.SourceInfo

/**
 * Encrypts one shared item off the Activity's lifecycle (spec §5). Expedited so
 * large videos finish after the share sheet is gone; the plaintext spool is
 * already app-private and owned by us before this runs (spec §5.1).
 */
class SaveMediaWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val d = inputData
        val tempPath = d.getString(KEY_TEMP) ?: return Result.failure()
        val request = SaveRequest(
            tempPath = tempPath,
            mimeType = d.getString(KEY_MIME) ?: "application/octet-stream",
            originalName = d.getString(KEY_NAME) ?: "shared",
            dateTakenMillis = d.getLong(KEY_DATE, System.currentTimeMillis()),
            tagNames = d.getStringArray(KEY_TAGS)?.toList().orEmpty(),
            source = SourceInfo(
                sourceType = runCatching { SourceType.valueOf(d.getString(KEY_SRC_TYPE) ?: "") }
                    .getOrDefault(SourceType.UNKNOWN),
                sourcePackage = d.getString(KEY_SRC_PKG),
                sourceLabel = d.getString(KEY_SRC_LABEL),
                sourceDomain = d.getString(KEY_SRC_DOMAIN),
            ),
        )

        val result = MediaSaver(applicationContext).save(request)
        // Retry transient failures once (e.g. Keystore hiccup); success is idempotent-ish
        // because a re-run recomputes the hash and dedups.
        return if (result.isSuccess) Result.success() else Result.retry()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = "vault_save"
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Saving to Link", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = Notification.Builder(applicationContext, channelId)
            .setContentTitle("Saving to Link")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIF_ID, notification)
    }

    companion object {
        private const val KEY_TEMP = "temp"
        private const val KEY_MIME = "mime"
        private const val KEY_NAME = "name"
        private const val KEY_DATE = "date"
        private const val KEY_TAGS = "tags"
        private const val KEY_SRC_TYPE = "srcType"
        private const val KEY_SRC_PKG = "srcPkg"
        private const val KEY_SRC_LABEL = "srcLabel"
        private const val KEY_SRC_DOMAIN = "srcDomain"
        private const val NOTIF_ID = 4711

        fun enqueue(context: Context, request: SaveRequest) {
            val data = Data.Builder()
                .putString(KEY_TEMP, request.tempPath)
                .putString(KEY_MIME, request.mimeType)
                .putString(KEY_NAME, request.originalName)
                .putLong(KEY_DATE, request.dateTakenMillis)
                .putStringArray(KEY_TAGS, request.tagNames.toTypedArray())
                .putString(KEY_SRC_TYPE, request.source.sourceType.name)
                .putString(KEY_SRC_PKG, request.source.sourcePackage)
                .putString(KEY_SRC_LABEL, request.source.sourceLabel)
                .putString(KEY_SRC_DOMAIN, request.source.sourceDomain)
                .build()

            val work = OneTimeWorkRequestBuilder<SaveMediaWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueue(work)
        }
    }
}
