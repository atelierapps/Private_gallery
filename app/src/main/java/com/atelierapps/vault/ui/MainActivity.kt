package com.atelierapps.vault.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
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
import com.atelierapps.vault.ui.albums.AlbumsActivity
import com.atelierapps.vault.ui.camera.CameraActivity
import com.atelierapps.vault.ui.export.ExportActivity
import com.atelierapps.vault.ui.restore.RestoreActivity
import com.atelierapps.vault.ui.rules.RulesActivity
import com.atelierapps.vault.ui.settings.SettingsActivity
import com.atelierapps.vault.ui.tags.TagsActivity
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
import com.atelierapps.vault.ui.theme.VaultTheme
import androidx.compose.ui.platform.LocalView
import com.atelierapps.vault.session.TileAnchor
import com.atelierapps.vault.ui.viewer.ViewerSession
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import com.atelierapps.vault.ui.theme.Bg

/**
 * Host for the vault UI (spec §8, §9). Shows the lock screen until a successful
 * auth, then the grid. FLAG_SECURE blocks screenshots / recents (spec §9).
 *
 * FragmentActivity because androidx BiometricPrompt requires it.
 */
class MainActivity : FragmentActivity() {

    /**
     * True between unlocking into a video and the viewer actually covering us.
     * Without it the grid composes for the frame or two that startActivity takes,
     * so unlocking flashes the library before the video you were watching —
     * brief, but it is the wrong thing to show and it draws the eye.
     */
    private var resumingToViewer by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        // We are visible again, so the viewer has been and gone.
        resumingToViewer = false
    }

    private val repository by lazy { VaultGraph.repository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            VaultTheme {
                // The window the viewer's open animation is measured against.
                val hostView = LocalView.current
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        val locked by VaultSession.locked.collectAsState()
                        if (locked) {
                            // Auto-prompt biometrics on open (spec §15.1). Re-runs if we re-lock.
                            LaunchedEffect(Unit) { promptUnlock() }
                            LockScreen(onUnlock = ::promptUnlock)
                        } else if (resumingToViewer) {
                            // Hold an empty surface rather than the grid for the
                            // moment before the viewer arrives on top.
                            Box(Modifier.fillMaxSize().background(Bg))
                        } else {
                            VaultHome(
                                onOpen = { id ->
                                    startActivity(
                                        ViewerActivity.intent(this@MainActivity, id),
                                        TileAnchor.consumeOptions(hostView),
                                    )
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
                                onAlbums = {
                                    startActivity(Intent(this@MainActivity, AlbumsActivity::class.java))
                                },
                                onTags = {
                                    startActivity(Intent(this@MainActivity, TagsActivity::class.java))
                                },
                                onSettings = {
                                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                                },
                                onExportSelection = { ids ->
                                    startActivity(ExportActivity.intent(this@MainActivity, ids))
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
                // Locked out of a video, unlocked back into it. Ordered after
                // the prewarm rather than driven off a composition effect, so
                // the keys are already being cached by the time the viewer asks
                // for them — and so it fires exactly once, on a real unlock.
                ViewerSession.consumeResume()?.let { id ->
                    resumingToViewer = true
                    startActivity(ViewerActivity.intent(this, id))
                }
            },
        )
    }

    /**
     * Background-unwrap every file's DEK after unlock (spec §3.1) so the grid
     * never stalls — and, crucially, so video playback never needs the Keystore
     * again this session. The Keystore key is only authorized for a short window
     * after biometric auth (§3.2); a video whose DEK wasn't yet cached would fail
     * to unwrap once that window lapsed, leaving a black screen until re-auth.
     * Caching both the thumbnail and blob DEKs here closes that gap. Measured
     * ~18 ms per RSA unwrap, so this fans out across a few workers off the UI
     * thread.
     */
    private fun prewarmDekCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            val storage = VaultGraph.storage(this@MainActivity)
            val ids = repository.allIds()
            val gate = Semaphore(PREWARM_CONCURRENCY)
            coroutineScope {
                ids.map { id ->
                    async {
                        gate.withPermit {
                            runCatching { MediaCrypto.prewarm(storage.thumb(id)) }
                            runCatching { MediaCrypto.prewarm(storage.blob(id)) }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private companion object {
        const val PREWARM_CONCURRENCY = 4
    }
}
