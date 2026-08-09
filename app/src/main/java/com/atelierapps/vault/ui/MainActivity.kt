package com.atelierapps.vault.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
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
import com.atelierapps.vault.ui.camera.CameraActivity
import com.atelierapps.vault.ui.export.ExportActivity
import com.atelierapps.vault.ui.restore.RestoreActivity
import com.atelierapps.vault.ui.rules.RulesActivity
import com.atelierapps.vault.ui.trash.TrashActivity
import com.atelierapps.vault.ui.home.VaultHome
import com.atelierapps.vault.ui.lock.LockScreen
import com.atelierapps.vault.ui.viewer.ViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
        enableEdgeToEdge()
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        val locked by VaultSession.locked.collectAsState()
                        if (locked) {
                            // Auto-prompt biometrics on open (spec §15.1). Re-runs if we re-lock.
                            LaunchedEffect(Unit) { promptUnlock() }
                            LockScreen(onUnlock = ::promptUnlock)
                        } else {
                            VaultHome(
                                onOpen = { id ->
                                    startActivity(ViewerActivity.intent(this@MainActivity, id))
                                },
                                onImport = {
                                    startActivity(Intent(this@MainActivity, ImportActivity::class.java))
                                },
                                onExport = {
                                    startActivity(Intent(this@MainActivity, ExportActivity::class.java))
                                },
                                onRestore = {
                                    startActivity(Intent(this@MainActivity, RestoreActivity::class.java))
                                },
                                onCamera = {
                                    startActivity(Intent(this@MainActivity, CameraActivity::class.java))
                                },
                                onTrash = {
                                    startActivity(Intent(this@MainActivity, TrashActivity::class.java))
                                },
                                onRules = {
                                    startActivity(Intent(this@MainActivity, RulesActivity::class.java))
                                },
                            )
                        }
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

    /**
     * Background-unwrap all thumbnail DEKs after unlock (spec §3.1) so the grid
     * never stalls. Measured ~18 ms per RSA unwrap on real hardware, so this runs
     * off the UI thread and fans out across a few workers to shorten the cold
     * fill on large libraries.
     */
    private fun prewarmDekCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            val storage = VaultGraph.storage(this@MainActivity)
            val ids = repository.allIds()
            val gate = Semaphore(PREWARM_CONCURRENCY)
            coroutineScope {
                ids.map { id ->
                    async {
                        gate.withPermit { runCatching { MediaCrypto.prewarm(storage.thumb(id)) } }
                    }
                }.awaitAll()
            }
        }
    }

    private companion object {
        const val PREWARM_CONCURRENCY = 4
    }
}
