package com.atelierapps.vault.crypto

import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache of unwrapped per-file DEKs — the §3.1 read-path mitigation.
 *
 * Unwrapping a DEK is an RSA-in-TEE op (tens of ms). Doing it per thumbnail on
 * every grid bind would make scrolling stutter. Instead we unwrap each DEK once
 * per unlocked session and keep it here; scrolling and rebinds hit the cache.
 * A background pre-warm after unlock (see the grid host) fills this off the UI
 * thread so the first paint is fast too.
 *
 * Cleared and zeroed on lock (spec §9). Keyed by absolute file path, since the
 * media blob and its thumbnail have different DEKs.
 */
object DekCache {
    private val map = ConcurrentHashMap<String, ByteArray>()

    /**
     * Bumped by [clear]. An unwrap that started before a lock must not put its
     * result into the cache afterwards: the post-unlock pre-warm fans out across
     * thousands of files, so there is almost always one in flight, and without
     * this a lock reliably left live DEKs behind — the one thing locking is for.
     */
    @Volatile private var generation = 0

    /** Cached DEK for [key], or [load] it (RSA unwrap) and cache. A rare race just re-unwraps. */
    fun getOrLoad(key: String, load: () -> ByteArray): ByteArray {
        map[key]?.let { return it }
        val startedIn = generation
        val dek = load()
        synchronized(this) {
            // Still the same session? Then it's safe to keep.
            if (generation == startedIn) map[key] = dek
        }
        return dek
    }

    fun size(): Int = map.size

    /** Drop and zero a single entry (e.g. when its media is deleted). */
    fun remove(key: String) {
        map.remove(key)?.let { Arrays.fill(it, 0) }
    }

    /** Zero every cached DEK and drop them (spec §9). */
    fun clear() {
        val values = synchronized(this) {
            generation++
            val snapshot = map.values.toList()
            map.clear()
            snapshot
        }
        values.forEach { Arrays.fill(it, 0) }
    }
}
