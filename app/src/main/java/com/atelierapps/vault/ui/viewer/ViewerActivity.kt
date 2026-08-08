package com.atelierapps.vault.ui.viewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState

/**
 * Hosts the full-screen viewer (spec §8). FLAG_SECURE blocks screenshots here
 * too. Loads a one-shot snapshot ordered like the grid and opens at the tapped
 * item.
 */
class ViewerActivity : ComponentActivity() {

    private val vm: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val startId = intent.getStringExtra(EXTRA_ID)
        vm.load()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val media by vm.media.collectAsState()
                val loaded by vm.loaded.collectAsState()
                if (loaded && media.isNotEmpty()) {
                    val startIndex = media.indexOfFirst { it.media.id == startId }.coerceAtLeast(0)
                    ViewerScreen(
                        media = media,
                        startIndex = startIndex,
                        onBack = { finish() },
                        onDelete = { id -> vm.delete(id) { finish() } },
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "id"
        fun intent(context: Context, id: String): Intent =
            Intent(context, ViewerActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
