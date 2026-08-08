package com.atelierapps.vault.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * Build-order step 1 (spec §13): envelope round-trip for GCM and CTR, plus the
 * CTR seek-to-offset test. Mirrors `tools/crypto-verify/EnvelopeVerify.java`,
 * run under Gradle: `./gradlew :app:test`.
 */
class EnvelopeCodecTest {

    private val wrapper = SoftwareKeyWrapper()
    private val rng = SecureRandom()

    private fun rand(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    // ---- GCM (images / thumbnails) ----

    @Test fun gcmRoundTripAcrossSizes() {
        for (size in intArrayOf(0, 1, 16, 1024, 5 * 1024 * 1024)) {
            val pt = rand(size)
            val file = EnvelopeCodec.encryptGcm(pt, wrapper)
            assertEquals(EnvelopeFormat.VERSION, file[0])
            assertEquals(EnvelopeFormat.MODE_GCM, file[1])
            assertArrayEquals("size=$size", pt, EnvelopeCodec.decryptGcm(file, wrapper))
        }
    }

    @Test fun gcmHeaderIsAuthenticated() {
        val file = EnvelopeCodec.encryptGcm(rand(4096), wrapper)
        file[2 + EnvelopeFormat.WRAPPED_DEK_LEN] = // first IV byte, part of AAD
            (file[2 + EnvelopeFormat.WRAPPED_DEK_LEN].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) {
            EnvelopeCodec.decryptGcm(file, wrapper)
        }
    }

    @Test fun gcmCiphertextTamperFails() {
        val file = EnvelopeCodec.encryptGcm(rand(4096), wrapper)
        file[file.size - 1] = (file[file.size - 1].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) {
            EnvelopeCodec.decryptGcm(file, wrapper)
        }
    }

    // ---- CTR (video) ----

    @Test fun ctrStreamRoundTrip() {
        val pt = rand(12 * 1024 * 1024)
        val sink = ByteArrayOutputStream()
        EnvelopeCodec.encryptCtr(ByteArrayInputStream(pt), sink, wrapper)
        val file = sink.toByteArray()
        assertEquals(EnvelopeFormat.MODE_CTR, file[1])
        assertArrayEquals(pt, EnvelopeCodec.decryptCtrFully(file, wrapper))
    }

    @Test fun ctrSeekToRandomOffsets() {
        val pt = rand(8 * 1024 * 1024)
        val file = encryptCtr(pt)
        val window = 64 * 1024
        repeat(100) {
            val offset = (Math.abs(rng.nextLong()) % (pt.size - window))
            assertArrayEquals(
                "offset=$offset",
                pt.copyOfRange(offset.toInt(), offset.toInt() + window),
                decryptWindow(file, offset, window),
            )
        }
    }

    @Test fun ctrSeekBoundaries() {
        val pt = rand(1 shl 20)
        val file = encryptCtr(pt)
        val offsets = longArrayOf(0, 1, 15, 16, 17, 31, 32, 4095, 4096, 4097,
            pt.size - 16L, pt.size - 1L)
        for (off in offsets) {
            val len = minOf(4096L, pt.size - off).toInt()
            assertArrayEquals(
                "offset=$off",
                pt.copyOfRange(off.toInt(), off.toInt() + len),
                decryptWindow(file, off, len),
            )
        }
    }

    // ---- helpers ----

    private fun encryptCtr(pt: ByteArray): ByteArray =
        ByteArrayOutputStream().also {
            EnvelopeCodec.encryptCtr(ByteArrayInputStream(pt), it, wrapper)
        }.toByteArray()

    private fun decryptWindow(file: ByteArray, offset: Long, len: Int): ByteArray {
        val wrapped = file.copyOfRange(2, 2 + EnvelopeFormat.WRAPPED_DEK_LEN)
        val iv = file.copyOfRange(
            2 + EnvelopeFormat.WRAPPED_DEK_LEN,
            EnvelopeFormat.headerLen(EnvelopeFormat.MODE_CTR),
        )
        CtrReader.open(wrapped, iv, wrapper).use { reader ->
            val cipher = reader.cipherAt(offset)
            val from = (reader.headerLen + offset).toInt()
            return cipher.update(file, from, len)
        }
    }
}
