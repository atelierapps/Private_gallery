package com.atelierapps.vault.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.crypto.MediaCrypto
import com.atelierapps.vault.data.entity.MediaItemEntity

/**
 * Moves a vault item back out to the device gallery (MediaStore) — the "take it
 * back out" flow. Decrypts to a fresh MediaStore entry under Pictures/Vault or
 * Movies/Vault; the caller removes the vault copy afterward to complete the move.
 *
 * This writes **plaintext** into the shared gallery, by design — it's the user
 * explicitly un-vaulting an item.
 */
object MediaExporter {

    fun toGallery(context: Context, item: MediaItemEntity): Boolean {
        val resolver = context.contentResolver
        val isVideo = item.mimeType.startsWith("video/")
        val collection =
            if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.originalName)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (isVideo) "${android.os.Environment.DIRECTORY_MOVIES}/Link"
                else "${android.os.Environment.DIRECTORY_PICTURES}/Link",
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri: Uri = resolver.insert(collection, values) ?: return false
        return try {
            val blob = VaultGraph.storage(context).blob(item.id)
            resolver.openOutputStream(uri)!!.use { out ->
                if (isVideo) MediaCrypto.decryptCtrTo(blob, out)
                else out.write(MediaCrypto.decryptGcmFile(blob))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (t: Throwable) {
            Log.e("MediaExporter", "toGallery failed for ${item.originalName}", t)
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
