package com.atelierapps.vault.crypto

/**
 * Wraps and unwraps a per-file DEK (spec §3, envelope encryption).
 *
 * The split is the whole point of the design:
 *  - [wrap] uses the RSA **public** key and requires **no** user auth, so
 *    saving from the share sheet never prompts for biometrics (spec §5).
 *  - [unwrap] uses the RSA **private** key, which is user-auth-required in the
 *    Keystore, so reading triggers auth (spec §9).
 *
 * Abstracting this boundary lets local JVM tests substitute a software RSA
 * keypair ([EnvelopeCodecTest]) while production uses [KeystoreKeyWrapper].
 */
interface KeyWrapper {
    /** Wrap a 32-byte DEK → 256-byte blob. No auth. */
    fun wrap(dek: ByteArray): ByteArray

    /** Unwrap a 256-byte blob → 32-byte DEK. Triggers user auth on device. */
    fun unwrap(wrapped: ByteArray): ByteArray
}
