package com.atelierapps.vault

import android.app.Application
import com.atelierapps.vault.share.ShareShortcut
import kotlin.concurrent.thread

/**
 * Application entry point.
 *  - Sweeps orphaned plaintext spools left by a crash mid-encrypt (spec §5.1).
 *  - Publishes the long-lived sharing shortcut so Vault is in the share sheet's
 *    top row (spec §5).
 *
 * Later steps add the ProcessLifecycleOwner auto-lock timer here (spec §9).
 */
class VaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        thread(name = "vault-startup") {
            VaultGraph.storage(this).sweepTemp(now = System.currentTimeMillis())
        }
        ShareShortcut.publish(this)
    }
}
