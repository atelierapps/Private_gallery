package com.atelierapps.vault.media

import android.util.Base64
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypting a backup with a passphrase, not with the device.
 *
 * The vault's own key lives in this phone's Keystore and dies with the install,
 * which is exactly why it cannot protect a backup: a backup you can only open
 * on the device you lost is not a backup. So the key here comes from something
 * you know instead — and there is no recovery, because a recovery path would be
 * a second way in, which is the thing the passphrase exists to prevent.
 *
 * ### Format
 * A plaintext `backup.json` header holds the KDF parameters and a verifier, so
 * a wrong passphrase is caught in a second rather than after four hundred files
 * of garbage. Everything else in the folder is ciphertext: each item is
 * `<uuid>.bin`, and even the manifest is encrypted, since filenames, tags and
 * sources are exactly the metadata the encrypted database now protects. Naming
 * the files by id rather than by title keeps that true of the directory listing.
 */
object BackupCrypto {

    const val HEADER = "backup.json"
    const val MANIFEST = "manifest.bin"
    /**
      * 2 since item files became framed. Nothing reads this to decide how to
      * decrypt — each item file says what it is — but a folder should still be
      * able to state which shape it was written in.
      */
    const val FORMAT_VERSION = 2

    /**
     * OWASP's floor for PBKDF2-HMAC-SHA256. Slow on purpose: this is the only
     * thing standing between a stolen folder and its contents, and a passphrase
     * a person can remember has far less entropy than the key it derives.
     */
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val VERIFIER_PLAINTEXT = "vault-backup-v1"

    class WrongPassphrase : Exception("passphrase does not match this backup")

    /** Header written beside the data so a restore knows how to derive the key. */
    data class Header(val version: Int, val iterations: Int, val salt: ByteArray, val verifier: ByteArray)

    fun newHeader(passphrase: CharArray): Pair<Header, SecretKey> {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt, ITERATIONS)
        val verifier = encryptBytes(key, VERIFIER_PLAINTEXT.toByteArray(Charsets.UTF_8))
        return Header(FORMAT_VERSION, ITERATIONS, salt, verifier) to key
    }

    fun headerToJson(header: Header): String = JSONObject().apply {
        put("format", header.version)
        put("kdf", "PBKDF2WithHmacSHA256")
        put("iterations", header.iterations)
        put("salt", Base64.encodeToString(header.salt, Base64.NO_WRAP))
        put("verifier", Base64.encodeToString(header.verifier, Base64.NO_WRAP))
    }.toString(2)

    fun headerFromJson(text: String): Header? = runCatching {
        val o = JSONObject(text)
        Header(
            version = o.optInt("format", 0),
            iterations = o.optInt("iterations", ITERATIONS),
            salt = Base64.decode(o.getString("salt"), Base64.NO_WRAP),
            verifier = Base64.decode(o.getString("verifier"), Base64.NO_WRAP),
        )
    }.getOrNull()

    /**
     * Derives the key and proves it against the header's verifier.
     *
     * @throws WrongPassphrase when it doesn't match, so the caller can say so
     *   plainly instead of failing item by item.
     */
    fun keyFor(header: Header, passphrase: CharArray): SecretKey {
        val key = deriveKey(passphrase, header.salt, header.iterations)
        val plain = runCatching { decryptBytes(key, header.verifier) }.getOrNull()
        if (plain == null || plain.toString(Charsets.UTF_8) != VERIFIER_PLAINTEXT) throw WrongPassphrase()
        return key
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        val bits = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bits, "AES")
    }

    // ---- whole-value helpers, for the header and the manifest ----

    fun encryptBytes(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decryptBytes(key: SecretKey, sealed: ByteArray): ByteArray {
        val iv = sealed.copyOfRange(0, IV_BYTES)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            doFinal(sealed.copyOfRange(IV_BYTES, sealed.size))
        }
    }

    // ---- streaming, for media that will not fit in memory ----

    /**
     * Magic that opens a framed item file. A v1 file opens with 12 random IV
     * bytes instead, so four fixed bytes tell the two apart with a one-in-four-
     * billion chance of being wrong — and the file says what it is rather than
     * relying on a manifest that might not be the one it was written with.
     */
    private val FRAME_MAGIC = byteArrayOf(0x56, 0x42, 0x4B, 0x32) // "VBK2"

    /**
     * Plaintext per frame. Peak memory is about twice this per item, so a
     * two-gigabyte video costs the same as a two-megabyte one.
     */
    private const val FRAME_BYTES = 1 shl 20 // 1 MiB

    /** Refuses an absurd frame length from a corrupt file before allocating it. */
    private const val FRAME_LIMIT = 64 shl 20

    /**
     * Writes a framed, encrypted copy of whatever [write] puts into the sink,
     * and returns how many plaintext bytes that was.
     *
     * ### Why frames, and not one cipher over the whole file
     * Because on Android one cipher over the whole file means the whole file in
     * memory. Conscrypt implements AES/GCM over BoringSSL's one-shot AEAD, so
     * `Cipher.update()` emits nothing at all — it copies its input into a buffer
     * that it doubles as it grows — and `doFinal()` seals the lot in a single
     * call. Streaming it was an illusion; `CipherOutputStream` did exactly the
     * same thing. Every video past a few hundred megabytes died with an
     * OutOfMemoryError and was reported, correctly but uselessly, as "failed".
     *
     * Each frame is sealed on its own, so the cost is one frame at a time. The
     * frame's index goes in as AAD, which is what stops frames being dropped,
     * repeated or swapped in a file that would otherwise still authenticate
     * frame by frame.
     */
    fun encryptTo(key: SecretKey, output: OutputStream, write: (OutputStream) -> Unit): Long {
        output.write(FRAME_MAGIC)
        output.write(intBytes(FRAME_BYTES))
        val sink = FramingSink(key, output)
        write(sink)
        // Not in close(): the caller owns `output`, and a failure sealing the
        // last frame has to reach the failure list rather than be swallowed the
        // way CipherOutputStream swallows its own final write.
        sink.finish()
        output.flush()
        return sink.plainBytes
    }

    /** Fills a frame, seals it, writes it, repeats. Never closes [out]. */
    private class FramingSink(
        private val key: SecretKey,
        private val out: OutputStream,
    ) : OutputStream() {
        private val frame = ByteArray(FRAME_BYTES)
        private var filled = 0
        private var index = 0L

        var plainBytes = 0L
            private set

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var from = off
            var left = len
            while (left > 0) {
                val take = minOf(left, FRAME_BYTES - filled)
                System.arraycopy(b, from, frame, filled, take)
                filled += take
                from += take
                left -= take
                plainBytes += take
                if (filled == FRAME_BYTES) seal()
            }
        }

        fun finish() {
            if (filled > 0) seal()
        }

        private fun seal() {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
                updateAAD(longBytes(index))
            }
            val sealed = cipher.doFinal(frame, 0, filled)
            out.write(intBytes(sealed.size))
            out.write(cipher.iv)
            out.write(sealed)
            index++
            filled = 0
        }

        override fun flush() = out.flush()
    }

    /**
     * Streams the plaintext of an item file out, and returns how many bytes that
     * was. Reads both the framed format and the single-cipher one written before
     * it, told apart by the magic.
     *
     * The caller **must** compare the result against the length the manifest
     * recorded. A wrong key or a truncated file surfaces as a short read rather
     * than an error — CipherInputStream reports a failed tag as end of stream —
     * so the length check is what turns that silence back into a failure.
     */
    fun decryptStream(key: SecretKey, input: InputStream, output: OutputStream): Long {
        val opening = ByteArray(FRAME_MAGIC.size)
        if (fill(input, opening) < opening.size) error("backup file is truncated before its header")
        return if (opening.contentEquals(FRAME_MAGIC)) {
            decryptFramed(key, input, output)
        } else {
            decryptWholeFile(key, opening, input, output)
        }
    }

    private fun decryptFramed(key: SecretKey, input: InputStream, output: OutputStream): Long {
        val sizeBytes = ByteArray(4)
        if (fill(input, sizeBytes) < 4) error("backup file is truncated before its header")
        val declared = intFrom(sizeBytes)
        var total = 0L
        var index = 0L
        val lengthBytes = ByteArray(4)
        while (true) {
            val got = fill(input, lengthBytes)
            if (got == 0) break // clean end of the last frame
            if (got < 4) error("backup item is truncated mid-frame")
            val length = intFrom(lengthBytes)
            // A corrupt length must not turn into a huge allocation.
            if (length <= 0 || length > minOf(FRAME_LIMIT, declared + 1024)) {
                error("backup item is truncated or corrupt")
            }
            val nonce = ByteArray(IV_BYTES)
            if (fill(input, nonce) < IV_BYTES) error("backup item is truncated mid-frame")
            val body = ByteArray(length)
            if (fill(input, body) < length) error("backup item is truncated mid-frame")
            val plain = Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(longBytes(index))
                doFinal(body)
            }
            output.write(plain)
            total += plain.size
            index++
        }
        return total
    }

    /**
     * The pre-framing layout: a 12-byte IV then one GCM stream. Kept so backups
     * already written still restore. It holds the whole item in memory, which is
     * the very thing framing exists to avoid — but no file large enough to be a
     * problem was ever written in this format, because writing one is exactly
     * what used to fail.
     */
    private fun decryptWholeFile(
        key: SecretKey,
        head: ByteArray,
        input: InputStream,
        output: OutputStream,
    ): Long {
        val iv = ByteArray(IV_BYTES)
        System.arraycopy(head, 0, iv, 0, head.size)
        val rest = ByteArray(IV_BYTES - head.size)
        if (fill(input, rest) < rest.size) error("backup file is truncated before its header")
        System.arraycopy(rest, 0, iv, head.size, rest.size)

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        var total = 0L
        CipherInputStream(input, cipher).use { decrypted ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = decrypted.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                total += read
            }
        }
        return total
    }

    /** Reads until [target] is full or the stream ends; returns how much it got. */
    private fun fill(input: InputStream, target: ByteArray): Int {
        var filled = 0
        while (filled < target.size) {
            val read = input.read(target, filled, target.size - filled)
            if (read <= 0) break
            filled += read
        }
        return filled
    }

    private fun intBytes(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun intFrom(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    private fun longBytes(v: Long): ByteArray = ByteArray(8) { i ->
        (v ushr (56 - 8 * i)).toByte()
    }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
}
