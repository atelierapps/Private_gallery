package com.atelierapps.vault.crypto

import java.security.KeyPair
import java.security.KeyPairGenerator
import javax.crypto.Cipher

/**
 * A software RSA-2048 stand-in for [KeystoreKeyWrapper], used only in local JVM
 * tests. Uses the identical JCE transformation as production, so the envelope
 * bytes are the same — the only thing that differs is where the private key
 * lives. The real Keystore key is exercised on-device by the instrumented
 * benchmark instead.
 */
class SoftwareKeyWrapper(
    private val keyPair: KeyPair = generate(),
) : KeyWrapper {

    override fun wrap(dek: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, keyPair.public)
            doFinal(dek)
        }

    override fun unwrap(wrapped: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, keyPair.private)
            doFinal(wrapped)
        }

    companion object {
        private const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        private fun generate(): KeyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
