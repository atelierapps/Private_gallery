package com.atelierapps.vault.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Production [KeyWrapper] backed by an Android Keystore RSA-2048 key (spec §3).
 *
 * Key `vault_wrap_v1`:
 *  - `RSA/ECB/OAEPWithSHA-256AndMGF1Padding`
 *  - user-auth-required — constrains the **private** key only, so [wrap]
 *    (public key) needs no auth and [unwrap] (private key) triggers it
 *  - 300s auth validity so a single unlock authorizes a session of reads
 *  - `setInvalidatedByBiometricEnrollment(false)` so enrolling a new
 *    fingerprint doesn't destroy the vault (accepted trade — spec §3.2)
 *
 * ### OAEP parameters must be pinned explicitly
 * Android Keystore's OAEP uses an **SHA-1 MGF1** regardless of the transformation
 * string, while the default software provider used for public-key encryption
 * would otherwise disagree — the mismatch throws `IllegalBlockSizeException` on
 * unwrap. So both sides init with an explicit [OAEPParameterSpec] (main digest
 * SHA-256, MGF1 SHA-1), and [wrap] re-imports the public key so encryption runs
 * in the software provider under our control. The key authorizes both SHA-256
 * and SHA-1 digests for the same reason.
 *
 * NB: Keystore keys never leave the device and cannot be migrated to a new
 * phone. Export (spec §11) is the only backup.
 */
class KeystoreKeyWrapper(
    private val alias: String = KEY_ALIAS,
) : KeyWrapper {

    private val keyStore: KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        if (!keyStore.containsAlias(alias)) generateKeyPair()
    }

    override fun wrap(dek: ByteArray): ByteArray {
        // Re-import the public key so encryption runs in the software provider,
        // where we fully control the OAEP parameters.
        val keystorePublic = keyStore.getCertificate(alias).publicKey
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(keystorePublic.encoded))
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, publicKey, oaepSpec())
            doFinal(dek)
        }
    }

    /**
     * Requires a valid auth within the key's validity window. Call after a
     * successful [androidx.biometric.BiometricPrompt] (spec §9). Throws
     * [android.security.keystore.UserNotAuthenticatedException] if the window
     * has lapsed — the caller re-prompts and retries.
     */
    override fun unwrap(wrapped: ByteArray): ByteArray {
        val privateKey = keyStore.getKey(alias, null)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, privateKey, oaepSpec())
            doFinal(wrapped)
        }
    }

    private fun generateKeyPair() {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(2048)
            // SHA-256 is the OAEP digest; SHA-1 is authorized for the MGF1.
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            .setInvalidatedByBiometricEnrollment(false)
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    companion object {
        const val KEY_ALIAS = "vault_wrap_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        private const val AUTH_VALIDITY_SECONDS = 300

        /** Main digest SHA-256, MGF1 SHA-1 — the combination Keystore actually uses. */
        fun oaepSpec(): OAEPParameterSpec =
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    }
}
