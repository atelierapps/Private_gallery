package com.atelierapps.vault.crypto

import java.io.File
import java.io.RandomAccessFile

/**
 * Cache-aware decryption for GCM blobs — thumbnails and full images (spec §8).
 * Reuses [DekCache] so a DEK is RSA-unwrapped at most once per unlocked session.
 * Video playback does not go through here (it streams via CTR, spec §8/§9).
 */
object MediaCrypto {

    /** Decrypt a GCM file into plaintext bytes, unwrapping its DEK via the cache. */
    fun decryptGcmFile(file: File, wrapper: KeyWrapper = VaultKeys.wrapper): ByteArray {
        val bytes = file.readBytes()
        val dek = DekCache.getOrLoad(file.absolutePath) {
            wrapper.unwrap(EnvelopeCodec.readWrappedDek(bytes))
        }
        return EnvelopeCodec.decryptGcmWithDek(bytes, dek)
    }

    /**
     * Warm the DEK cache for a file without decrypting its body — reads only the
     * 258-byte header and unwraps. Used to pre-warm all thumbnails after unlock
     * (spec §3.1) so the first grid paint doesn't pay RSA on the UI path.
     */
    fun prewarm(file: File, wrapper: KeyWrapper = VaultKeys.wrapper) {
        if (!file.exists()) return
        DekCache.getOrLoad(file.absolutePath) {
            val wrapped = ByteArray(EnvelopeFormat.WRAPPED_DEK_LEN)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(2)
                raf.readFully(wrapped)
            }
            wrapper.unwrap(wrapped)
        }
    }
}
