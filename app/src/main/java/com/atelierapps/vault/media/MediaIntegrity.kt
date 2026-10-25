package com.atelierapps.vault.media

import android.content.Context
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.crypto.EnvelopeFormat
import com.atelierapps.vault.crypto.MediaCrypto
import com.atelierapps.vault.data.entity.MediaItemEntity
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Is this item still exactly what was imported?
 *
 * Every row carries the SHA-256 of its plaintext, taken at import before the
 * file was encrypted. Decrypting the blob and hashing it again is therefore a
 * yes-or-no answer, not a guess: a match means the bytes at rest are, to the
 * bit, the bytes that came in, and anything wrong with how the item plays lies
 * somewhere else — the player, the decoder, or the file as it was when it
 * arrived. A mismatch means the vault's copy has changed underneath it.
 *
 * That distinction is the whole point. "This video won't play" and "this video
 * is damaged" look identical on screen and call for opposite responses, and the
 * player alone can't tell them apart.
 */
object MediaIntegrity {

    enum class Verdict {
        /** Decrypts to exactly the imported bytes. */
        OK,

        /** The row exists but its encrypted file does not. */
        MISSING,

        /** The encrypted file is the wrong length for what was imported. */
        WRONG_SIZE,

        /** Decrypts, but to different bytes from the ones imported. */
        CHANGED,

        /** Couldn't be read to find out — see the reason. */
        UNREADABLE,

        /** Imported before hashes were kept, so only its size could be checked. */
        UNVERIFIABLE,
    }

    data class Report(val verdict: Verdict, val reason: String)

    /**
     * Reads the item's blob in full. Slow for a large video, and meant to be:
     * nothing short of reading every byte can say the content is unchanged.
     */
    fun check(context: Context, item: MediaItemEntity): Report {
        val blob = VaultGraph.storage(context).blob(item.id)
        if (!blob.exists()) {
            return Report(Verdict.MISSING, "its encrypted file is missing from the vault")
        }

        val mode = item.cryptoMode.toByte()
        val header = runCatching { EnvelopeFormat.headerLen(mode) }.getOrNull()
            ?: return Report(Verdict.UNREADABLE, "its record names an encryption mode this app doesn't know")
        val tag = if (mode == EnvelopeFormat.MODE_GCM) EnvelopeFormat.GCM_TAG_LEN else 0
        val expected = header + item.sizeBytes + tag
        val actual = blob.length()
        if (actual != expected) {
            return Report(
                Verdict.WRONG_SIZE,
                "its encrypted file is " + describeSize(actual) + " where it should be " +
                    describeSize(expected) + " — it has been cut short or added to",
            )
        }

        val recorded = item.contentHash
            ?: return Report(Verdict.UNVERIFIABLE, "imported before content checks existed; its size is right")

        val hash = try {
            plaintextHash(blob, mode)
        } catch (t: Throwable) {
            // A GCM image whose tag no longer verifies is damage, not a read
            // problem, and should be reported as such.
            if (t.javaClass.simpleName.contains("AEADBadTag")) {
                return Report(Verdict.CHANGED, "its encrypted file is damaged and no longer authenticates")
            }
            return Report(Verdict.UNREADABLE, TransferFailure.describe(t))
        }
        return if (hash == recorded) {
            Report(Verdict.OK, "")
        } else {
            Report(Verdict.CHANGED, "it decrypts to different bytes from the ones that were imported")
        }
    }

    /** Same digest and same hex formatting MediaSaver records at import. */
    private fun plaintextHash(blob: File, mode: Byte): String {
        val md = MessageDigest.getInstance("SHA-256")
        if (mode == EnvelopeFormat.MODE_CTR) {
            // Streamed: a two-gigabyte video is hashed without being held.
            MediaCrypto.decryptCtrTo(blob, DigestSink(md))
        } else {
            md.update(MediaCrypto.decryptGcmFile(blob))
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private class DigestSink(private val md: MessageDigest) : OutputStream() {
        override fun write(b: Int) = md.update(b.toByte())
        override fun write(b: ByteArray, off: Int, len: Int) = md.update(b, off, len)
    }

    private fun describeSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes bytes"
    }
}
