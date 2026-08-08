package com.atelierapps.vault.crypto

/**
 * Process-wide access to the vault's [KeyWrapper]. Lazily creates/loads the
 * Keystore RSA key on first use. Swapped for a software wrapper in tests.
 */
object VaultKeys {
    @Volatile private var override: KeyWrapper? = null

    val wrapper: KeyWrapper by lazy { override ?: KeystoreKeyWrapper() }

    /** Test seam. */
    fun useForTesting(wrapper: KeyWrapper) { override = wrapper }
}
