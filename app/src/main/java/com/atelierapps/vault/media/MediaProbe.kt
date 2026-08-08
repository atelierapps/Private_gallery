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

/** Reads dimensions/duration/date from a plaintext file, without decoding pixels. */
object MediaProbe {

    fun probe(file: File, mimeType: String, fallbackDateMillis: Long): MediaMeta =
        if (mimeType.startsWith("video/")) probeVideo(file, fallbackDateMillis)
        else probeImage(file, fallbackDateMillis)

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
