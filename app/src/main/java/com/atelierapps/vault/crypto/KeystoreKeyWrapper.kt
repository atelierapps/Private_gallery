package com.atelierapps.vault.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import javax.crypto.Cipher

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
        val publicKey = keyStore.getCertificate(alias).publicKey
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, publicKey)
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
            init(Cipher.DECRYPT_MODE, privateKey)
            doFinal(wrapped)
        }
    }

    private fun generateKeyPair() {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
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
    }
}
