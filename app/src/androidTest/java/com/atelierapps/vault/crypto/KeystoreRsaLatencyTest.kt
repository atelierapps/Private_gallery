package com.atelierapps.vault.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Spec §3.1 go/no-go: measure a single TEE RSA-OAEP unwrap on real hardware.
 * This × grid size ≈ the cold-grid stall, and decides whether the naive
 * per-thumbnail read path is shippable or whether the session-key / library-key
 * strategy is required before the grid (step 4) is built.
 *
 * Device-only: `./gradlew :app:connectedCheck`. Read the numbers in logcat
 * under the tag "VaultRsaBench".
 *
 * To isolate the *crypto* cost from the biometric prompt, this uses a throwaway
 * key with auth NOT required — same size/padding as `vault_wrap_v1`. It measures
 * the TEE RSA operation, which is the part that scales with grid size; the auth
 * prompt happens once per unlock, not per thumbnail.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreRsaLatencyTest {

    private val benchAlias = "vault_bench_rsa"
    private val transformation = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @After fun cleanup() {
        if (keyStore.containsAlias(benchAlias)) keyStore.deleteEntry(benchAlias)
    }

    @Test fun measureUnwrapLatency() {
        generateBenchKey()
        // Re-import the public key so encryption runs in the software provider,
        // and pin the OAEP params so encrypt/decrypt agree (see KeystoreKeyWrapper).
        val pub = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(keyStore.getCertificate(benchAlias).publicKey.encoded))
        val priv = keyStore.getKey(benchAlias, null)
        val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = Cipher.getInstance(transformation).run {
            init(Cipher.ENCRYPT_MODE, pub, oaep); doFinal(dek)
        }

        // warm up
        repeat(10) {
            Cipher.getInstance(transformation).run { init(Cipher.DECRYPT_MODE, priv, oaep); doFinal(wrapped) }
        }

        val iterations = 100
        val samples = LongArray(iterations)
        for (i in 0 until iterations) {
            val t0 = System.nanoTime()
            Cipher.getInstance(transformation).run { init(Cipher.DECRYPT_MODE, priv, oaep); doFinal(wrapped) }
            samples[i] = System.nanoTime() - t0
        }
        samples.sort()
        val medianMs = samples[iterations / 2] / 1_000_000.0
        val p90Ms = samples[(iterations * 90) / 100] / 1_000_000.0

        for (n in intArrayOf(50, 200, 500)) {
            Log.i(TAG, "cold grid N=$n → ~${"%.0f".format(medianMs * n)} ms (median × N)")
        }
        Log.i(TAG, "single unwrap: median=${"%.2f".format(medianMs)} ms  p90=${"%.2f".format(p90Ms)} ms")
        Log.i(TAG, "DECISION: if N=200 median×N > ~300 ms, adopt the session-key re-wrap (§3.1).")
    }

    private fun generateBenchKey() {
        val spec = KeyGenParameterSpec.Builder(
            benchAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(false) // isolate crypto cost from the auth prompt
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(spec); generateKeyPair()
        }
    }

    companion object { private const val TAG = "VaultRsaBench" }
}
