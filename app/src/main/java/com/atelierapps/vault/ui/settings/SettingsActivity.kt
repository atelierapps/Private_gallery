package com.atelierapps.vault.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.atelierapps.vault.ui.theme.VaultTheme

/** App settings (spec §7, §9). FLAG_SECURE like the rest of the vault. */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            VaultTheme {
                Surface(Modifier.fillMaxSize()) {
                    SettingsScreen(
                        onClose = { finish() },
                        onStorage = {
                            startActivity(
                                android.content.Intent(
                                    this,
                                    com.atelierapps.vault.ui.storage.StorageActivity::class.java,
                                ),
                            )
                        },
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }
}
