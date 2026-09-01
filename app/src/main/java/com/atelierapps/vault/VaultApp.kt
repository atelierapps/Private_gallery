package com.atelierapps.vault

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.atelierapps.vault.crypto.DekCache
import com.atelierapps.vault.media.MediaSharer
import com.atelierapps.vault.storage.VaultStorage
import com.atelierapps.vault.ui.trash.TrashViewModel
import com.atelierapps.vault.session.LockPrefs
import com.atelierapps.vault.session.VaultSession
import com.atelierapps.vault.share.ShareShortcut
import com.atelierapps.vault.ui.image.VaultImageLoader
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import com.atelierapps.vault.session.LockButtonPrefs
import com.atelierapps.vault.session.BackupPrefs
import com.atelierapps.vault.media.TransferWake

/** How often the deferred auto-lock re-checks that a transfer has ended. */
private const val TRANSFER_POLL_MS = 5_000L

/**
 * Application entry point.
 *  - Provides the app-wide Coil [ImageLoader] with the decrypting fetcher and
 *    no disk cache (spec §8).
 *  - Sweeps orphaned plaintext spools left by a crash mid-encrypt (spec §5.1).
 *  - Publishes the long-lived sharing shortcut (spec §5).
 *  - Auto-locks the vault after it's been backgrounded for the configured delay
 *    (spec §9): zeroes cached DEKs and clears the Coil memory cache.
 */
class VaultApp : Application(), SingletonImageLoader.Factory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        thread(name = "vault-startup") {
            val storage = VaultGraph.storage(this)
            storage.sweepTemp(now = System.currentTimeMillis())
            purgeExpiredTrash(storage)
            runCatching { MediaSharer.sweep(this) } // drop leftover share spools
        }
        ShareShortcut.publish(this)
        registerAutoLock()
        LockButtonPrefs.load(this)
        BackupPrefs.load(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        VaultImageLoader.build(context)

    private fun registerAutoLock() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var lockJob: Job? = null

            override fun onStop(owner: LifecycleOwner) {
                lockJob = appScope.launch {
                    val delayMs = LockPrefs.delayMs(this@VaultApp)
                    if (delayMs > 0) delay(delayMs)
                    // A backup keeps running when you leave the screen — that is
                    // the whole point of the wake lock. Locking on the timer
                    // would then zero the DEK cache underneath it, and every
                    // remaining item would fail: the Keystore window has long
                    // since lapsed and there is nobody there to re-authorise an
                    // unwrap. So wait the run out. Coming back to the app
                    // cancels this job anyway, and the lock still lands the
                    // moment the transfer is done.
                    while (TransferWake.busy) delay(TRANSFER_POLL_MS)
                    lockNow()
                }
            }

            override fun onStart(owner: LifecycleOwner) {
                lockJob?.cancel()
            }
        })
    }

    private fun lockNow() {
        VaultSession.lock() // zeroes cached DEKs
        runCatching { SingletonImageLoader.get(this).memoryCache?.clear() }
    }

    /**
     * Permanently purge recycle-bin items past the retention window (spec §8).
     * Runs off the main thread at launch; the blob is the only copy, so this is
     * the single place trashed media actually leaves the disk on its own.
     */
    private fun purgeExpiredTrash(storage: VaultStorage) {
        runCatching {
            runBlocking {
                val repo = VaultGraph.repository(this@VaultApp)
                val cutoff = System.currentTimeMillis() - TrashViewModel.RETENTION_MS
                repo.expiredTrashIds(cutoff).forEach { id ->
                    repo.purgeMedia(id)
                    DekCache.remove(storage.thumb(id).absolutePath)
                    DekCache.remove(storage.blob(id).absolutePath)
                    storage.delete(id)
                }
            }
        }
    }
}
