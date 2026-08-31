package com.atelierapps.vault.ui.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.atelierapps.vault.ui.theme.VaultTheme
import com.atelierapps.vault.ui.lock.FinishOnLock

/**
 * Hosts the full-screen viewer (spec §8). FLAG_SECURE blocks screenshots here
 * too. Loads a one-shot snapshot ordered like the grid and opens at the tapped
 * item.
 */
class ViewerActivity : ComponentActivity() {

    private val vm: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Force dark, transparent system bars so nothing white shows over the
        // black media surface (MIUI's default light nav scrim = the "white chin").
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        // Route the hardware volume buttons to the media stream so they raise/
        // lower video sound while the viewer is open.
        volumeControlStream = AudioManager.STREAM_MUSIC

        // Immersive: hide the status/nav bars (clock, signal) during viewing;
        // a swipe from the edge brings them back transiently.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val startId = intent.getStringExtra(EXTRA_ID)
        vm.load()

        setContent {
            FinishOnLock { finish() }
            VaultTheme {
                val media by vm.media.collectAsState()
                val loaded by vm.loaded.collectAsState()
                val albums by vm.albums.collectAsState()
                val allTags by vm.tags.collectAsState()
                if (loaded && media.isNotEmpty()) {
                    val startIndex = media.indexOfFirst { it.media.id == startId }.coerceAtLeast(0)
                    ViewerScreen(
                        media = media,
                        startIndex = startIndex,
                        onBack = { finish() },
                        onDelete = { id -> vm.delete(id) { finish() } },
                        onTogglePin = { id -> vm.togglePin(id) },
                        albums = albums,
                        allTags = allTags,
                        onSetTags = { id, names -> vm.setTags(id, names) },
                        onRename = { id, name -> vm.rename(id, name) },
                        onAddToAlbum = { id, albumId -> vm.addToAlbum(id, albumId) },
                        onAddToNewAlbum = { id, name -> vm.addToNewAlbum(id, name) },
                        onShare = { id ->
                            vm.share(
                                id,
                                onReady = { uri, mime -> startShareChooser(uri, mime) },
                                onError = {
                                    Toast.makeText(this, "Couldn't prepare share", Toast.LENGTH_SHORT).show()
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    private fun startShareChooser(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(
            Intent.createChooser(send, "Share").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    companion object {
        private const val EXTRA_ID = "id"
        fun intent(context: Context, id: String): Intent =
            Intent(context, ViewerActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
