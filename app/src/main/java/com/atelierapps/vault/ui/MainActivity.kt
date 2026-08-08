package com.atelierapps.vault.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.auth.BiometricAuth
import com.atelierapps.vault.crypto.MediaCrypto
import com.atelierapps.vault.imports.ImportActivity
import com.atelierapps.vault.session.VaultSession
import com.atelierapps.vault.ui.home.VaultHome
import com.atelierapps.vault.ui.lock.LockScreen
import com.atelierapps.vault.ui.viewer.ViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Host for the vault UI (spec §8, §9). Shows the lock screen until a successful
 * auth, then the grid. FLAG_SECURE blocks screenshots / recents (spec §9).
 *
 * FragmentActivity because androidx BiometricPrompt requires it.
 */
class MainActivity : FragmentActivity() {

    private val repository by lazy { VaultGraph.repository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier) {
                    val locked by VaultSession.locked.collectAsState()
                    if (locked) {
                        // Auto-prompt biometrics on open (spec §15.1). Re-runs if we re-lock.
                        LaunchedEffect(Unit) { promptUnlock() }
                        LockScreen(onUnlock = ::promptUnlock)
                    } else {
                        VaultHome(
                            onOpen = { id -> startActivity(ViewerActivity.intent(this, id)) },
                            onImport = { startActivity(Intent(this, ImportActivity::class.java)) },
                        )
                    }
                }
            }
        }
    }

    private fun promptUnlock() {
        BiometricAuth.authenticate(
            this,
            onSuccess = {
                VaultSession.markUnlocked()
                prewarmDekCache()
            },
        )
    }

    /** Background-unwrap all thumbnail DEKs after unlock (spec §3.1) so the grid never stalls. */
    private fun prewarmDekCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            val storage = VaultGraph.storage(this@MainActivity)
            repository.allIds().forEach { id -> MediaCrypto.prewarm(storage.thumb(id)) }
        }
    }
}
