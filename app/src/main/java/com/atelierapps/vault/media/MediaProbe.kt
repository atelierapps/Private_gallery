package com.atelierapps.vault.media

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File

/** Probed intrinsic metadata for a media file (spec §2). */
data class MediaMeta(
    val widthPx: Int,
    val heightPx: Int,
    val durationMillis: Long?,
    val dateTakenMillis: Long,
)

/**
 * Reads dimensions/duration/date from a plaintext file, without decoding pixels.
 *
 * Never throws. What comes back is a nicety — the size under a photo, the length
 * on a video tile — and MediaMetadataRetriever rejects more files than the player
 * does. Letting it throw meant a clip it could not parse failed the whole save,
 * blob and all, so the vault refused to store media it was perfectly able to
 * keep. Unknown metadata is a blank field; it is not a reason to lose the file.
 */
object MediaProbe {

    fun probe(file: File, mimeType: String, fallbackDateMillis: Long): MediaMeta =
        if (mimeType.startsWith("video/")) {
            runCatching { probeVideo(file, fallbackDateMillis) }
                .getOrDefault(MediaMeta(0, 0, null, fallbackDateMillis))
        } else {
            runCatching { probeImage(file, fallbackDateMillis) }
                .getOrDefault(MediaMeta(0, 0, null, fallbackDateMillis))
        }

    private fun probeImage(file: File, fallbackDate: Long): MediaMeta {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return MediaMeta(
            widthPx = opts.outWidth.coerceAtLeast(0),
            heightPx = opts.outHeight.coerceAtLeast(0),
            durationMillis = null,
            dateTakenMillis = fallbackDate,
        )
    }

    private fun probeVideo(file: File, fallbackDate: Long): MediaMeta {
        MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(file.absolutePath)
            fun int(key: Int) = mmr.extractMetadata(key)?.toIntOrNull() ?: 0
            fun long(key: Int) = mmr.extractMetadata(key)?.toLongOrNull()
            return MediaMeta(
                widthPx = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                heightPx = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                durationMillis = long(MediaMetadataRetriever.METADATA_KEY_DURATION),
                dateTakenMillis = fallbackDate,
            )
        }
    }
}
