package com.atelierapps.vault.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The passphrase for the encrypted metadata database.
 *
 * A random 32 bytes, generated once, stored on disk wrapped by a hardware-backed
 * Keystore AES key. The passphrase never exists in plaintext at rest and the
 * wrapping key can't be extracted from the device.
 *
 * ### Why this key is not gated on biometrics, unlike the media key
 * [KeystoreKeyWrapper] requires user auth, which works there because it is RSA:
 * wrapping uses the *public* key and needs no auth, so saving media works with
 * the vault locked, while reading it back needs a recent unlock. A database
 * passphrase has no such split — every read and every write needs it, and the
 * app writes rows with no user present: the share-target saves without
 * unlocking (spec §5), and the import worker drains its queue in the
 * background. An auth-gated database key would break both.
 *
 * So this raises the bar from "readable by anything that can read the
 * filesystem" — a backup extraction, a stolen unencrypted disk image, another
 * app on a rooted phone — to "requires this device's Keystore". It is not a
 * defence against code already running as this app; nothing here is.
 */
object DbKeyStore {

    private const val ALIAS = "vault_db_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val FILE = "db.key"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val PASSPHRASE_BYTES = 32

    @Volatile private var cached: ByteArray? = null

    /** The database passphrase, generating and persisting one on first call. */
    @Synchronized
    fun passphrase(context: Context): ByteArray {
        cached?.let { return it }
        val file = File(context.filesDir, FILE)
        val bytes =
            if (file.exists()) {
                unseal(file.readBytes())
            } else {
                val fresh = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
                // Temp-file-and-rename: a half-written key file would make the
                // database permanently unopenable, which is worse than any crash
                // this could interrupt.
                val tmp = File(context.filesDir, "$FILE.tmp")
                tmp.writeBytes(seal(fresh))
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    error("could not persist database key")
                }
                fresh
            }
        cached = bytes
        return bytes
    }

    /** Hex form, for the PRAGMA/ATTACH raw-key syntax that skips the KDF. */
    fun passphraseHex(context: Context): String =
        passphrase(context).joinToString("") { "%02x".format(it) }

    private fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(plain)
        return cipher.iv + ct
    }

    private fun unseal(sealed: ByteArray): ByteArray {
        val iv = sealed.copyOfRange(0, IV_BYTES)
        val ct = sealed.copyOfRange(IV_BYTES, sealed.size)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            doFinal(ct)
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
        }.generateKey()
    }
}
