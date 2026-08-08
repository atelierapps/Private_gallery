package com.atelierapps.vault.auth

import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Biometric / device-credential unlock (spec §9, §15.1). No custom PIN —
 * device credential is stronger and already Keystore-backed.
 *
 * The vault key uses time-based auth (`setUserAuthenticationParameters(300,…)`,
 * spec §3.2), so a plain prompt success authorizes the private key for the
 * window — no `CryptoObject` needed here.
 */
object BiometricAuth {

    fun canAuthenticate(activity: FragmentActivity): Int =
        androidx.biometric.BiometricManager.from(activity)
            .canAuthenticate(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)

    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (CharSequence) -> Unit = {},
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString)
                }
                // onAuthenticationFailed (one bad attempt) leaves the prompt open — ignore.
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Vault")
            .setSubtitle("Your media is encrypted on this device")
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }
}
