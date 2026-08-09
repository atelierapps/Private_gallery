package com.atelierapps.vault.ui.albums

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import com.atelierapps.vault.ui.viewer.ViewerActivity

/** Shows one album's contents (spec §7). FLAG_SECURE like the rest of the vault. */
class AlbumActivity : ComponentActivity() {

    private val vm: AlbumViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val albumId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        val albumName = intent.getStringExtra(EXTRA_NAME) ?: "Album"
        vm.setAlbum(albumId)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    AlbumScreen(
                        vm = vm,
                        albumName = albumName,
                        onOpen = { id -> startActivity(ViewerActivity.intent(this, id)) },
                        onClose = { finish() },
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "album_id"
        private const val EXTRA_NAME = "album_name"
        fun intent(context: Context, id: String, name: String): Intent =
            Intent(context, AlbumActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_NAME, name)
    }
}
