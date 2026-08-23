package com.atelierapps.vault.imports

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.media.MediaSaver
import com.atelierapps.vault.media.SaveRequest
import com.atelierapps.vault.media.SourceInfo

/**
 * Drains [ImportQueue] off the Activity's lifecycle (spec §4), so a bulk import
 * keeps running when you leave the importer — or the app.
 *
 * Deliberately **not** expedited: a large batch would blow through the expedited
 * quota, and expedited work wants a foreground notification, which is at odds
 * with the app staying inconspicuous (§10). A plain worker may be stopped and
 * retried instead; that's safe because the queue is consumed item-by-item and
 * content-hash dedup (§4.2) makes a re-run idempotent.
 */
class ImportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val storage = VaultGraph.storage(ctx)
        val saver = MediaSaver(ctx)

        while (true) {
            if (isStopped) return Result.retry() // resume from the queue next run
            val entry = ImportQueue.peek(ctx) ?: break

            val outcome = try {
                val temp = storage.newTempFile()
                ctx.contentResolver.openInputStream(entry.uri).use { input ->
                    if (input == null) throw IllegalStateException("no stream for ${entry.uri}")
                    temp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                }
                val result = saver.save(
                    SaveRequest(
                        tempPath = temp.absolutePath,
                        mimeType = entry.mimeType,
                        originalName = entry.displayName,
                        dateTakenMillis = entry.dateTakenMillis,
                        tagNames = emptyList(),
                        // The item's own gallery folder is its provenance.
                        source = SourceInfo(entry.origin, null, entry.bucketName, null),
                    ),
                )
                when {
                    result.getOrNull() == MediaSaver.DUPLICATE -> ImportQueue.Outcome.DUPLICATE
                    result.isSuccess -> ImportQueue.Outcome.SAVED
                    else -> ImportQueue.Outcome.FAILED
                }
            } catch (t: Throwable) {
                Log.w(TAG, "import failed for ${entry.uri}", t)
                ImportQueue.Outcome.FAILED
            }

            ImportQueue.completeFirst(ctx, outcome, entry.uri)
            val s = ImportQueue.state(ctx)
            setProgress(
                Data.Builder()
                    .putInt(KEY_DONE, s.done)
                    .putInt(KEY_TOTAL, s.total)
                    .putInt(KEY_DUPES, s.duplicates)
                    .putInt(KEY_FAILED, s.failed)
                    .build(),
            )
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "VaultImport"
        const val WORK_NAME = "vault_import"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_DUPES = "dupes"
        const val KEY_FAILED = "failed"

        /**
         * Append work and make sure a drainer is running. KEEP means a second
         * batch started while one is in flight is simply picked up by the worker
         * already draining the queue.
         */
        fun enqueue(context: Context, entries: List<ImportQueue.Entry>, deleteOriginals: Boolean) {
            ImportQueue.enqueue(context, entries, deleteOriginals)
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ImportWorker>().build(),
            )
        }
    }
}
