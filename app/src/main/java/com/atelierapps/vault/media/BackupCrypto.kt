package com.atelierapps.vault.media

import android.util.Base64
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
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
    const val FORMAT_VERSION = 1

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
     * Writes the IV, then hands [write] a sink that encrypts everything put into
     * it. Returns how many plaintext bytes went in.
     *
     * Inverted like this so the vault's own decryption can write *directly* into
     * the backup's cipher: the plaintext exists only as it passes between two
     * ciphers, never as a buffer and never as a file.
     */
    fun encryptTo(key: SecretKey, output: OutputStream, write: (OutputStream) -> Unit): Long {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        output.write(cipher.iv)
        val counted = CountingSink(CipherOutputStream(output, cipher))
        counted.use(write)
        return counted.bytes
    }

    /** Counts plaintext on its way into the cipher, so the manifest can record it. */
    private class CountingSink(private val delegate: OutputStream) : OutputStream() {
        var bytes = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytes++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytes += len
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    /**
     * Reads the IV, then streams the plaintext out, and returns how many bytes
     * that was.
     *
     * The caller **must** compare that against the length recorded in the
     * manifest. CipherInputStream swallows a failed GCM tag and reports end of
     * stream instead of throwing, so a tampered or truncated file would restore
     * as a short but plausible-looking one. The length check is what turns that
     * silence back into an error.
     */
    fun decryptStream(key: SecretKey, input: InputStream, output: OutputStream): Long {
        val iv = ByteArray(IV_BYTES)
        var filled = 0
        while (filled < IV_BYTES) {
            val read = input.read(iv, filled, IV_BYTES - filled)
            if (read <= 0) error("backup file is truncated before its header")
            filled += read
        }
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

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
}
