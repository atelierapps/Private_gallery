package com.atelierapps.vault

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.atelierapps.vault.share.ShareShortcut
import com.atelierapps.vault.ui.image.VaultImageLoader
import kotlin.concurrent.thread

/**
 * Application entry point.
 *  - Provides the app-wide Coil [ImageLoader] with the decrypting fetcher and
 *    no disk cache (spec §8).
 *  - Sweeps orphaned plaintext spools left by a crash mid-encrypt (spec §5.1).
 *  - Publishes the long-lived sharing shortcut (spec §5).
 *
 * Later steps add the ProcessLifecycleOwner auto-lock timer here (spec §9).
 */
class VaultApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        thread(name = "vault-startup") {
            VaultGraph.storage(this).sweepTemp(now = System.currentTimeMillis())
        }
        ShareShortcut.publish(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        VaultImageLoader.build(context)
}
