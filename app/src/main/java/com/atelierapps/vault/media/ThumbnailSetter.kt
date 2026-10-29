package com.atelierapps.vault.media

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import coil3.SingletonImageLoader
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.crypto.DekCache
import com.atelierapps.vault.crypto.EnvelopeCodec
import com.atelierapps.vault.crypto.EnvelopeFormat
import com.atelierapps.vault.crypto.VaultKeys
import java.io.File
import java.io.FileOutputStream

/**
 * Replace an item's thumbnail with a frame the owner picked.
 *
 * The frame arrives as a bitmap captured from the preview's own surface, so no
 * plaintext is decoded to a file at any point. It is encrypted under a fresh
 * key exactly as an import's thumbnail is (wrapping needs only the public key,
 * so no unlock window is involved), written beside the current thumbnail,
 * checked for the length an envelope of that size must have, and only then
 * renamed over it. A failure anywhere leaves the old thumbnail as it was.
 *
 * The thumbnail is cosmetic — the video itself is never touched — but the
 * same verify-then-swap costs nothing and means a crash can't leave a tile
 * with no picture.
 */
object ThumbnailSetter {

    fun set(context: Context, id: String, frame: Bitmap): Boolean {
        val storage = VaultGraph.storage(context)
        val thumb = storage.thumb(id)
        val side = File(thumb.parentFile, thumb.name + ".new")
        return try {
            val jpeg = Thumbnailer.jpegFrom(frame)
            if (jpeg.isEmpty()) return false
            val sealed = EnvelopeCodec.encryptGcm(jpeg, VaultKeys.wrapper)
            FileOutputStream(side).use { out ->
                out.write(sealed)
                out.flush()
                out.fd.sync()
            }
            val expected = EnvelopeFormat.headerLen(EnvelopeFormat.MODE_GCM) + jpeg.size + EnvelopeFormat.GCM_TAG_LEN
            check(side.length() == expected.toLong()) { "thumbnail length " + side.length() + " != " + expected }
            check(side.renameTo(thumb)) { "couldn't swap the thumbnail in" }
            // The old thumbnail's key is cached under this same path; left
            // there, it would be tried on the new file and fail every time.
            DekCache.remove(thumb.absolutePath)
            // And the decoded old picture is in Coil's memory cache.
            runCatching { SingletonImageLoader.get(context).memoryCache?.clear() }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "thumbnail not replaced for " + id, t)
            side.delete()
            false
        }
    }

    private const val TAG = "ThumbnailSetter"
}
