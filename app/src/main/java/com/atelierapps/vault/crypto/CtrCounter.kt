package com.atelierapps.vault.crypto

import java.math.BigInteger

/**
 * AES-CTR counter arithmetic for seeking into encrypted video (spec §3, §8).
 *
 * Mirrors Media3's `AesCipherDataSource` / `AesFlushingCipher` so that a seek
 * lands on exactly the same keystream ExoPlayer expects:
 *
 *   counter(offset) = (IV_as_128bit + offset / 16) mod 2^128
 *   then discard (offset % 16) keystream bytes to realign to the byte offset.
 *
 * Verified against 200 random offsets on a 100 MB buffer plus every block
 * boundary in `tools/crypto-verify/EnvelopeVerify.java`.
 */
object CtrCounter {

    private val TWO_POW_128: BigInteger = BigInteger.ONE.shiftLeft(128)

    /** The 16-byte big-endian counter block for the AES block containing [byteOffset]. */
    fun counterForOffset(iv16: ByteArray, byteOffset: Long): ByteArray {
        require(iv16.size == EnvelopeFormat.CTR_IV_LEN) { "CTR IV must be 16 bytes" }
        val blockIndex = byteOffset / 16
        val value = BigInteger(1, iv16).add(BigInteger.valueOf(blockIndex)).mod(TWO_POW_128)
        return to16Bytes(value)
    }

    /** Bytes of keystream to skip after seeking to [byteOffset]'s block start. */
    fun skipWithinBlock(byteOffset: Long): Int = (byteOffset % 16).toInt()

    private fun to16Bytes(v: BigInteger): ByteArray {
        val raw = v.toByteArray() // may carry a leading sign byte, or be short
        val out = ByteArray(16)
        if (raw.size <= 16) {
            System.arraycopy(raw, 0, out, 16 - raw.size, raw.size)
        } else {
            System.arraycopy(raw, raw.size - 16, out, 0, 16)
        }
        return out
    }
}
