package com.atelierapps.vault.ui.lock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.atelierapps.vault.session.VaultSession

/**
 * Closes a screen the moment the vault locks.
 *
 * Only MainActivity watched the lock state, so an auto-lock while the viewer,
 * an album or the importer was on top left decrypted media sitting on screen:
 * the keys were zeroed, but the already-decoded frame was still being drawn.
 * Locking has to mean every screen showing vault content goes away, not just
 * the one behind them.
 */
@Composable
fun FinishOnLock(onLocked: () -> Unit) {
    val locked by VaultSession.locked.collectAsState()
    LaunchedEffect(locked) { if (locked) onLocked() }
}
