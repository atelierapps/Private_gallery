package com.atelierapps.vault.crypto

import java.io.Closeable
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Random-access decryptor for a CTR video envelope (spec §3, §8, §9).
 *
 * Unwraps the DEK **once** (one auth-gated RSA private-key op) and keeps it live
 * for the session, so scrubbing doesn't re-authenticate on every seek. The
 * Media3 `FileDataSource` wrapper feeds ciphertext through [cipherAt] positioned
 * at the requested plaintext offset.
 *
 * The DEK is wiped by [close] — called on lock (spec §9).
 */
class CtrReader private constructor(
    private val dek: ByteArray,
    private val iv: ByteArray,
) : Closeable {

    val headerLen: Int = EnvelopeFormat.headerLen(EnvelopeFormat.MODE_CTR)

    /**
     * An AES/CTR [Cipher] whose keystream is aligned to plaintext [byteOffset]:
     * the counter is advanced to the containing block and the partial-block
     * keystream is consumed. Feed ciphertext from file offset
     * `headerLen + byteOffset` onward via [Cipher.update].
     */
    fun cipherAt(byteOffset: Long): Cipher {
        val counter = CtrCounter.counterForOffset(iv, byteOffset)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), IvParameterSpec(counter))
        val skip = CtrCounter.skipWithinBlock(byteOffset)
        if (skip > 0) cipher.update(ByteArray(skip)) // discard to realign
        return cipher
    }

    override fun close() {
        Arrays.fill(dek, 0)
    }

    companion object {
        fun open(wrapped: ByteArray, iv: ByteArray, wrapper: KeyWrapper): CtrReader {
            require(iv.size == EnvelopeFormat.CTR_IV_LEN) { "CTR IV must be 16 bytes" }
            return CtrReader(wrapper.unwrap(wrapped), iv.copyOf())
        }
    }
}
