package com.atelierapps.vault.crypto

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reads and writes the [EnvelopeFormat] blobs (spec §3).
 *
 * GCM path — images and thumbnails. Authenticated: the header is bound as AAD
 * and any tamper throws on decrypt. Buffered in memory (these are small).
 *
 * CTR path — video. Seekable via [CtrReader]; **not** authenticated (CTR gives
 * no integrity — an accepted trade for scrubbable playback under this threat
 * model, spec §3.2). Streamed so multi-GB files never fully materialize.
 *
 * DEKs are held only in mutable [ByteArray]s and zeroed after use; [CtrReader]
 * keeps its DEK live for the session and is wiped by [CtrReader.close].
 */
object EnvelopeCodec {

    private val rng = SecureRandom()

    // ---------------- GCM (images, thumbnails) ----------------

    fun encryptGcm(plaintext: ByteArray, wrapper: KeyWrapper): ByteArray {
        val dek = ByteArray(EnvelopeFormat.DEK_LEN).also { rng.nextBytes(it) }
        try {
            val wrapped = wrapper.wrap(dek)
            require(wrapped.size == EnvelopeFormat.WRAPPED_DEK_LEN) {
                "wrapped DEK must be ${EnvelopeFormat.WRAPPED_DEK_LEN} bytes"
            }
            val iv = ByteArray(EnvelopeFormat.GCM_IV_LEN).also { rng.nextBytes(it) }
            val header = buildHeader(EnvelopeFormat.MODE_GCM, wrapped, iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(dek, "AES"),
                GCMParameterSpec(EnvelopeFormat.GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(header)
            val body = cipher.doFinal(plaintext)

            return header + body
        } finally {
            Arrays.fill(dek, 0)
        }
    }

    fun decryptGcm(file: ByteArray, wrapper: KeyWrapper): ByteArray {
        require(file.size >= EnvelopeFormat.headerLen(EnvelopeFormat.MODE_GCM)) { "truncated GCM blob" }
        require(file[0] == EnvelopeFormat.VERSION) { "unsupported format version ${file[0]}" }
        require(file[1] == EnvelopeFormat.MODE_GCM) { "not a GCM blob (mode ${file[1]})" }

        val headerLen = EnvelopeFormat.headerLen(EnvelopeFormat.MODE_GCM)
        val wrapped = file.copyOfRange(2, 2 + EnvelopeFormat.WRAPPED_DEK_LEN)
        val iv = file.copyOfRange(2 + EnvelopeFormat.WRAPPED_DEK_LEN, headerLen)
        val body = file.copyOfRange(headerLen, file.size)

        val dek = wrapper.unwrap(wrapped)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(dek, "AES"),
                GCMParameterSpec(EnvelopeFormat.GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(file.copyOfRange(0, headerLen))
            return cipher.doFinal(body) // throws AEADBadTagException on tamper
        } finally {
            Arrays.fill(dek, 0)
        }
    }

    // ---------------- CTR (video) ----------------

    /** Stream-encrypt [source] into [sink] as a CTR envelope. Closes neither stream. */
    fun encryptCtr(source: InputStream, sink: OutputStream, wrapper: KeyWrapper) {
        val dek = ByteArray(EnvelopeFormat.DEK_LEN).also { rng.nextBytes(it) }
        try {
            val wrapped = wrapper.wrap(dek)
            require(wrapped.size == EnvelopeFormat.WRAPPED_DEK_LEN) {
                "wrapped DEK must be ${EnvelopeFormat.WRAPPED_DEK_LEN} bytes"
            }
            val iv = ByteArray(EnvelopeFormat.CTR_IV_LEN).also { rng.nextBytes(it) }
            sink.write(buildHeader(EnvelopeFormat.MODE_CTR, wrapped, iv))

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), IvParameterSpec(iv))

            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buf)
                if (n < 0) break
                val enc = cipher.update(buf, 0, n)
                if (enc != null && enc.isNotEmpty()) sink.write(enc)
            }
            val fin = cipher.doFinal()
            if (fin != null && fin.isNotEmpty()) sink.write(fin)
            sink.flush()
        } finally {
            Arrays.fill(dek, 0)
        }
    }

    /** Convenience full-file CTR decrypt — used by tests; playback uses [CtrReader]. */
    fun decryptCtrFully(file: ByteArray, wrapper: KeyWrapper): ByteArray {
        CtrReader.open(
            wrapped = file.copyOfRange(2, 2 + EnvelopeFormat.WRAPPED_DEK_LEN),
            iv = file.copyOfRange(
                2 + EnvelopeFormat.WRAPPED_DEK_LEN,
                EnvelopeFormat.headerLen(EnvelopeFormat.MODE_CTR),
            ),
            wrapper = wrapper,
        ).use { reader ->
            val headerLen = EnvelopeFormat.headerLen(EnvelopeFormat.MODE_CTR)
            val cipher = reader.cipherAt(0)
            return cipher.doFinal(file, headerLen, file.size - headerLen)
        }
    }

    // ---------------- header helpers ----------------

    private fun buildHeader(mode: Byte, wrapped: ByteArray, iv: ByteArray): ByteArray {
        val header = ByteArray(2 + EnvelopeFormat.WRAPPED_DEK_LEN + iv.size)
        header[0] = EnvelopeFormat.VERSION
        header[1] = mode
        System.arraycopy(wrapped, 0, header, 2, EnvelopeFormat.WRAPPED_DEK_LEN)
        System.arraycopy(iv, 0, header, 2 + EnvelopeFormat.WRAPPED_DEK_LEN, iv.size)
        return header
    }
}
