package com.atelierapps.vault.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.crypto.VaultCtrDataSource
import com.atelierapps.vault.data.entity.MediaWithTags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
// Media3 has its own ExportResult, and this package already has one (backups).
import androidx.media3.transformer.ExportResult as TransformerResult

/**
 * Keep one stretch of a video, as a new item.
 *
 * **The one place plaintext video touches disk.** Media3's Transformer only
 * writes to a file path — its MP4 muxer seeks back through a FileChannel to
 * fill in the index, so there is no stream to encrypt through. The owner chose
 * to accept that: the trimmed part is written to `tmp/trim-<uuid>.mp4` in
 * app-private storage (unreadable to other apps, under the phone's own
 * file-based encryption), lives for the few seconds it takes to encrypt, and
 * is deleted in `finally` whatever happens. A run that dies outright leaves it
 * for the launch sweep, which removes trim files regardless of age. It is not
 * overwritten first: on flash storage an overwrite lands in fresh cells and
 * erases nothing, so it would be theatre.
 *
 * **The original is never at risk.** Nothing about it is read except through
 * the same decrypting source the player uses, and it is moved to Recently
 * deleted — only if asked, and only after the new copy has been probed as a
 * playable video, saved, and decrypted back to exactly the bytes that were
 * encrypted ([MediaIntegrity]). Any failure before that leaves the original
 * exactly as it was.
 *
 * No effects are applied, so Transformer copies the compressed video rather
 * than re-encoding it: fast, and no quality is lost. The price is that a cut
 * can only start on a keyframe, so the kept part may begin up to a second or
 * two before the chosen point.
 */
@UnstableApi
object VideoTrimmer {

    /** Anything shorter isn't worth keeping and risks an empty output. */
    const val MIN_KEEP_MILLIS = 1_000L

    /**
     * Returns the new item's id. On failure the message is a sentence for the
     * screen, and nothing in the library has changed (bar a binned new copy
     * that failed its check).
     */
    suspend fun trim(
        context: Context,
        original: MediaWithTags,
        startMs: Long,
        endMs: Long,
        replace: Boolean,
        /** Which part of a split this is (1-based), for its name; null for a plain trim. */
        part: Int? = null,
        // Last, so callers can pass it as a trailing lambda.
        onProgress: (Int) -> Unit,
    ): Result<String> {
        val app = context.applicationContext
        if (endMs - startMs < MIN_KEEP_MILLIS) {
            return Result.failure(IllegalArgumentException("keep at least a second"))
        }
        val media = original.media
        val out = VaultGraph.storage(app).newTrimFile()
        try {
            // Transformer must be driven from one Looper thread; the main one,
            // as its docs recommend.
            val error = withContext(Dispatchers.Main) {
                runTransformer(app, media.id, startMs, endMs, out.absolutePath, onProgress)
            }
            if (error != null) {
                Log.e(TAG, "trim failed", error)
                return Result.failure(IllegalStateException("the video couldn't be cut — " + describe(error)))
            }
            // Encrypting a long clip is seconds of work; MediaSaver does it on
            // the caller's thread, so this half must not be on the main one.
            return withContext(Dispatchers.IO) { keep(app, original, out, replace, part) }
        } finally {
            // Normally MediaSaver has already deleted it after encrypting.
            out.delete()
        }
    }

    /** Everything after the cut: check it, encrypt it in, verify, carry extras, bin. */
    private suspend fun keep(
        app: Context,
        original: MediaWithTags,
        out: java.io.File,
        replace: Boolean,
        part: Int?,
    ): Result<String> {
        val media = original.media
        probe(out)?.let { return Result.failure(IllegalStateException(it)) }

        val saver = MediaSaver(app)
        val newId = saver.save(
            SaveRequest(
                tempPath = out.absolutePath,
                mimeType = "video/mp4",
                originalName = trimmedName(media.originalName, part),
                dateTakenMillis = media.dateTakenMillis,
                tagNames = original.tags.map { it.name },
                source = SourceInfo(
                    media.sourceType, media.sourcePackage, media.sourceLabel, media.sourceDomain,
                ),
            ),
        ).getOrElse { return Result.failure(IllegalStateException("the cut part couldn't be saved")) }
        if (newId == MediaSaver.DUPLICATE) {
            return Result.failure(IllegalStateException("that part is identical to something already here"))
        }

        val repo = VaultGraph.repository(app)
        val saved = repo.mediaById(newId)?.media
            ?: return Result.failure(IllegalStateException("the cut part didn't appear in the library"))
        val report = MediaIntegrity.check(app, saved)
        if (report.verdict != MediaIntegrity.Verdict.OK) {
            // The new copy isn't sound. Bin it rather than leave a doubtful
            // item in the library, and keep the original untouched.
            repo.trashMedia(newId)
            return Result.failure(IllegalStateException("the cut part didn't check out (" + report.reason + ")"))
        }

        // Everything else the original carried. Best-effort: the trimmed
        // video is already safe, and none of this is worth failing over.
        runCatching {
            media.albumId?.let { repo.setAlbumForItems(setOf(newId), it) }
            media.note?.let { repo.setNote(newId, it) }
            if (media.isPinned) repo.setPinned(newId, true)
            media.videoRotationDegrees?.let { repo.setVideoRotation(newId, it) }
        }.onFailure { Log.w(TAG, "extras not carried to " + newId, it) }

        if (replace) repo.trashMedia(media.id)
        return Result.success(newId)
    }

    private suspend fun runTransformer(
        context: Context,
        id: String,
        startMs: Long,
        endMs: Long,
        path: String,
        onProgress: (Int) -> Unit,
    ): Throwable? = coroutineScope {
        // Reads the original through the player's own decrypting source, so the
        // input never exists in plaintext anywhere.
        val assets = ExoPlayerAssetLoader.Factory(
            context,
            DefaultDecoderFactory.Builder(context).build(),
            Clock.DEFAULT,
            DefaultMediaSourceFactory(VaultCtrDataSource.Factory(context)),
        )
        val item = MediaItem.Builder()
            .setUri(VaultCtrDataSource.uriFor(id))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build(),
            )
            .build()
        val done = CompletableDeferred<Throwable?>()
        val transformer = Transformer.Builder(context)
            .setAssetLoaderFactory(assets)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: TransformerResult) {
                    done.complete(null)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: TransformerResult,
                    exportException: ExportException,
                ) {
                    done.complete(exportException)
                }
            })
            .build()
        transformer.start(EditedMediaItem.Builder(item).build(), path)
        val poller = launch {
            val holder = ProgressHolder()
            while (isActive) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress)
                }
                delay(250)
            }
        }
        try {
            done.await()
        } finally {
            poller.cancel()
            // Leaving the screen cancels the coroutine; stop the work with it.
            if (!done.isCompleted) transformer.cancel()
        }
    }

    /** Null when the output is a real video; otherwise why it isn't. */
    private fun probe(file: java.io.File): String? {
        if (!file.exists() || file.length() == 0L) return "the cut produced nothing"
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            when {
                !hasVideo -> "the cut part has no picture"
                duration <= 0L -> "the cut part has no length"
                else -> null
            }
        } catch (t: Throwable) {
            "the cut part couldn't be read back"
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun describe(error: Throwable): String {
        val code = (error as? ExportException)?.errorCodeName
        return when {
            code == null -> TransferFailure.describe(error)
            code.contains("DECOD") -> "this phone couldn't decode it"
            code.contains("ENCOD") -> "this phone couldn't encode it"
            code.contains("MUXING") -> "it couldn't be written as an MP4"
            else -> code.lowercase().replace('_', ' ')
        }
    }

    /**
     * "clip.mp4" becomes "clip (trimmed).mp4", or "clip (part 2).mp4" for a
     * split; a second trim doesn't stack the suffix.
     */
    private fun trimmedName(name: String, part: Int?): String {
        val stem = name.substringBeforeLast('.', name)
            .replace(Regex(" \\((trimmed|part \\d+)\\)$"), "")
        val suffix = if (part == null) " (trimmed)" else " (part $part)"
        return "$stem$suffix.mp4"
    }

    private const val TAG = "VideoTrimmer"
}
