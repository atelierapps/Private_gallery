package com.atelierapps.vault.crypto

/**
 * On-disk envelope format for encrypted media blobs and thumbnails (spec §3).
 *
 *   [1B version][1B mode][256B wrapped DEK][IV][ciphertext(+16B GCM tag)]
 *
 * - The per-file DEK is 32 fresh [SecureRandom] bytes, wrapped with the vault's
 *   RSA public key (no auth to write) and unwrapped with the private key
 *   (auth to read). See [KeyWrapper].
 * - Mode selects the AES cipher: GCM for images/thumbnails (authenticated),
 *   CTR for video (seekable, unauthenticated — see the note in [EnvelopeCodec]).
 * - The full header (version, mode, wrapped DEK, IV) is fed as GCM AAD so a
 *   tampered/spliced header never decrypts.
 *
 * This layout and the CTR counter math in [CtrCounter] are verified end-to-end
 * by `tools/crypto-verify/EnvelopeVerify.java` and `EnvelopeCodecTest`.
 */
object EnvelopeFormat {
    const val VERSION: Byte = 1

    const val MODE_GCM: Byte = 1  // images + thumbnails
    const val MODE_CTR: Byte = 2  // video

    const val WRAPPED_DEK_LEN = 256 // RSA-2048 OAEP output
    const val GCM_IV_LEN = 12       // NIST-recommended 96-bit nonce
    const val CTR_IV_LEN = 16       // full initial counter block
    const val GCM_TAG_BITS = 128
    const val GCM_TAG_LEN = 16
    const val DEK_LEN = 32          // AES-256

    fun ivLen(mode: Byte): Int = when (mode) {
        MODE_GCM -> GCM_IV_LEN
        MODE_CTR -> CTR_IV_LEN
        else -> throw IllegalArgumentException("unknown envelope mode $mode")
    }

    /** Bytes before the ciphertext for [mode]: version + mode + wrapped DEK + IV. */
    fun headerLen(mode: Byte): Int = 2 + WRAPPED_DEK_LEN + ivLen(mode)
}
