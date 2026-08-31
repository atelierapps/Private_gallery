package com.atelierapps.vault.media

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the CPU running through a long export or restore.
 *
 * Without this the screen going off suspends the work: twenty-five minutes into
 * a restore the phone sleeps and the whole run is lost, which is the worst
 * possible outcome for an operation nobody wants to repeat. A partial lock
 * leaves the screen off — it only stops the processor being parked.
 *
 * Reference counted, because an export and a restore could in principle overlap,
 * and always released in a finally. The timeout is a backstop and nothing else:
 * a lock leaked by a bug would flatten the battery, so it expires on its own
 * well after any plausible transfer has finished.
 */
object TransferWake {

    private const val TAG = "TransferWake"
    private const val TAG_LOCK = "vault:transfer"
    private const val TIMEOUT_MS = 4L * 60 * 60 * 1000 // 4 hours

    private var lock: PowerManager.WakeLock? = null
    private var holders = 0

    @Synchronized
    fun acquire(context: Context) {
        holders++
        if (lock?.isHeld == true) return
        runCatching {
            val power = context.getSystemService(PowerManager::class.java)
            lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG_LOCK).apply {
                setReferenceCounted(false)
                acquire(TIMEOUT_MS)
            }
        }.onFailure { Log.e(TAG, "could not hold the CPU awake", it) }
    }

    @Synchronized
    fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders > 0) return
        runCatching { lock?.takeIf { it.isHeld }?.release() }
            .onFailure { Log.e(TAG, "releasing the wake lock failed", it) }
        lock = null
    }
}
