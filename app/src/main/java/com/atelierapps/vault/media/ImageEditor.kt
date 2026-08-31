package com.atelierapps.vault.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.crypto.MediaCrypto
import com.atelierapps.vault.data.entity.MediaWithTags
import java.io.ByteArrayOutputStream

/**
 * Rotate and crop, applied through the ordinary save pipeline.
 *
 * Nothing here writes to a blob directly: the edited pixels go out to a spool
 * file and through [MediaSaver], so they get the same encryption, the same
 * fsync-and-verify, and the same thumbnail generation as anything else. An
 * editor that hand-rolls its own write path is an editor that eventually writes
 * a blob the rest of the app can't read.
 *
 * Replacing sends the original to the recycle bin rather than deleting it.
 * Cropping is not reversible from the pixels, so the thirty-day window is the
 * only undo there is — and it costs nothing until it lapses.
 */
object ImageEditor {

    private const val TAG = "ImageEditor"
    private const val QUALITY = 95

    /** Decodes the full-size image for editing, or null if it can't be decoded. */
    fun decode(context: Context, id: String): Bitmap? = runCatching {
        val bytes = MediaCrypto.decryptGcmFile(VaultGraph.storage(context).blob(id))
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        )
    }.onFailure { Log.e(TAG, "decode failed for " + id, it) }.getOrNull()

    /**
     * Applies [rotationDegrees] then [crop], where crop is in normalised
     * coordinates of the *rotated* image — the same space the editor's overlay
     * works in, so what was framed is what comes out.
     */
    fun transform(
        source: Bitmap,
        rotationDegrees: Int,
        crop: NormalisedRect,
    ): Bitmap {
        val rotated =
            if (rotationDegrees % 360 == 0) {
                source
            } else {
                Bitmap.createBitmap(
                    source, 0, 0, source.width, source.height,
                    Matrix().apply { postRotate(rotationDegrees.toFloat()) },
                    true,
                )
            }
        if (crop.isWhole) return rotated

        val x = (crop.left * rotated.width).toInt().coerceIn(0, rotated.width - 1)
        val y = (crop.top * rotated.height).toInt().coerceIn(0, rotated.height - 1)
        val w = (crop.width * rotated.width).toInt().coerceIn(1, rotated.width - x)
        val h = (crop.height * rotated.height).toInt().coerceIn(1, rotated.height - y)
        return Bitmap.createBitmap(rotated, x, y, w, h)
    }

    /**
     * Encrypts [bitmap] as a new vault item carrying the original's tags, album
     * and source, and bins the original when [replace] is set.
     *
     * @return the new item's id, or null if nothing was written.
     */
    suspend fun save(
        context: Context,
        original: MediaWithTags,
        bitmap: Bitmap,
        replace: Boolean,
    ): String? {
        val storage = VaultGraph.storage(context)
        val repo = VaultGraph.repository(context)
        val spool = storage.newTempFile()

        val ok = runCatching {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                spool.writeBytes(out.toByteArray())
            }
        }.isSuccess
        if (!ok) {
            spool.delete()
            return null
        }

        val media = original.media
        val result = MediaSaver(context).save(
            SaveRequest(
                tempPath = spool.absolutePath,
                mimeType = "image/jpeg",
                originalName = editedName(media.originalName),
                dateTakenMillis = media.dateTakenMillis,
                tagNames = original.tags.map { it.name },
                source = SourceInfo(
                    media.sourceType, media.sourcePackage, media.sourceLabel, media.sourceDomain,
                ),
            ),
        )
        val newId = result.getOrNull()
        if (newId == null || newId == MediaSaver.DUPLICATE) {
            // DUPLICATE means the edit produced bytes already in the vault, so
            // there is nothing new to keep and certainly nothing to bin.
            return null
        }

        media.albumId?.let { repo.setAlbumForItems(setOf(newId), it) }
        if (replace) repo.trashMedia(media.id)
        return newId
    }

    /** "beach.jpg" becomes "beach (edited).jpg", and stays that on a second pass. */
    private fun editedName(name: String): String {
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        val base = if (stem.endsWith(" (edited)")) stem else stem + " (edited)"
        return if (extension.isEmpty()) base else base + ".jpg"
    }
}

/** A crop box in 0..1 coordinates, so it survives rotation and rescaling. */
data class NormalisedRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    val isWhole: Boolean
        get() = left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f

    companion object {
        val WHOLE = NormalisedRect()
    }
}
