package com.atelierapps.vault.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.crypto.MediaCrypto
import com.atelierapps.vault.data.entity.MediaItemEntity
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Share-out (spec §11): decrypt one item to a short-lived spool under
 * `cacheDir/share` and expose it to another app the user explicitly picks, via a
 * per-share [FileProvider] content URI with a temporary read grant. The vault
 * blobs never leave `filesDir`; only this transient plaintext copy is shared, and
 * it's swept on the next share and at launch.
 *
 * This is the user deliberately handing one item to another app — the same
 * decrypt they already do to view it — so it is not separately auth-gated (unlike
 * a full-library export, which is).
 */
object MediaSharer {

    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private val SPOOL_TTL_MS = 10 * 60 * 1000L

    /** Decrypt [item] to the share spool and return a grantable content URI. */
    fun decryptForShare(context: Context, item: MediaItemEntity): Uri {
        val spool = File(context.cacheDir, "share").apply { mkdirs() }
        sweep(spool)
        // One sub-dir per share so the receiving app sees the real filename.
        val dir = File(spool, UUID.randomUUID().toString()).apply { mkdirs() }
        val out = File(dir, safeName(item))

        val blob = VaultGraph.storage(context).blob(item.id)
        FileOutputStream(out).use { os ->
            if (item.mimeType.startsWith("video/")) MediaCrypto.decryptCtrTo(blob, os)
            else os.write(MediaCrypto.decryptGcmFile(blob))
        }
        return FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, out)
    }

    /** Remove stale share spools (call at launch; done automatically per share). */
    fun sweep(context: Context) = sweep(File(context.cacheDir, "share"))

    private fun sweep(spool: File) {
        if (!spool.exists()) return
        val cutoff = System.currentTimeMillis() - SPOOL_TTL_MS
        spool.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) f.deleteRecursively()
        }
    }

    private fun safeName(item: MediaItemEntity): String =
        item.originalName.ifBlank { item.id }.replace(Regex("[/\\\\]"), "_")
}
