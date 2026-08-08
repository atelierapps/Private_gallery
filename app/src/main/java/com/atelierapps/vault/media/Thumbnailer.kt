package com.atelierapps.vault.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File

/** Builds a 512px JPEG thumbnail (spec §2, §8). Output is encrypted by the caller. */
object Thumbnailer {

    private const val MAX_EDGE = 512
    private const val JPEG_QUALITY = 82

    fun jpegBytes(file: File, mimeType: String): ByteArray {
        val bitmap = (if (mimeType.startsWith("video/")) videoFrame(file) else imageBitmap(file))
            ?: return ByteArray(0)
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun imageBitmap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return scaleDown(decoded, MAX_EDGE)
    }

    private fun videoFrame(file: File): Bitmap? =
        MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(file.absolutePath)
            val frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame?.let { scaleDown(it, MAX_EDGE) }
        }

    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var sample = 1
        var longest = maxOf(w, h)
        while (longest / 2 >= target) { longest /= 2; sample *= 2 }
        return sample
    }

    private fun scaleDown(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest
        val out = Bitmap.createScaledBitmap(
            src, (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1), true,
        )
        if (out != src) src.recycle()
        return out
    }
}
