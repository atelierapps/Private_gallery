package com.atelierapps.vault.crypto

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
     * Stream-decrypt a CTR video blob into [out] (used to move a video back out
     * to the gallery). Reuses the cached DEK; never buffers the whole file.
     */
    fun decryptCtrTo(file: File, out: OutputStream, wrapper: KeyWrapper = VaultKeys.wrapper) {
        val headerLen = EnvelopeFormat.headerLen(EnvelopeFormat.MODE_CTR)
        val header = ByteArray(headerLen)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        val wrapped = header.copyOfRange(2, 2 + EnvelopeFormat.WRAPPED_DEK_LEN)
        val iv = header.copyOfRange(2 + EnvelopeFormat.WRAPPED_DEK_LEN, headerLen)
        val dek = DekCache.getOrLoad(file.absolutePath) { wrapper.unwrap(wrapped) }

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), IvParameterSpec(iv))
        FileInputStream(file).use { input ->
            var skipped = 0L
            while (skipped < headerLen) skipped += input.skip(headerLen - skipped)
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                cipher.update(buf, 0, n)?.let { if (it.isNotEmpty()) out.write(it) }
            }
            cipher.doFinal()?.let { if (it.isNotEmpty()) out.write(it) }
        }
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
