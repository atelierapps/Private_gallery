package com.atelierapps.vault.session

import com.atelierapps.vault.crypto.DekCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lock state for the vault UI (spec §9). Starts locked; the grid is shown only
 * after a successful auth. Locking zeroes the cached DEKs ([DekCache]).
 *
 * Step 10 extends this with the ProcessLifecycleOwner auto-lock timer and the
 * Coil memory-cache clear; step 4 wires the manual/at-launch path.
 */
object VaultSession {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun markUnlocked() { _locked.value = false }

    fun lock() {
        _locked.value = true
        DekCache.clear()
        // Deliberately does NOT clear ViewerSession. The keys are gone, which is
        // what matters; what stays is where you were and how far into it — in
        // memory only, dying with the process — so unlocking returns you to the
        // video you were watching instead of the top of the grid.
    }
}
