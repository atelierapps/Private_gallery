package com.atelierapps.vault.ui.duplicates

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
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.atelierapps.vault.ui.theme.VaultTheme
import com.atelierapps.vault.ui.lock.FinishOnLock

/** Hosts the duplicate finder. FLAG_SECURE like the rest of the vault. */
class DuplicatesActivity : ComponentActivity() {

    private val vm: DuplicatesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            FinishOnLock { finish() }
            VaultTheme {
                Surface(Modifier.fillMaxSize()) {
                    DuplicatesScreen(vm = vm, onClose = { finish() }, modifier = Modifier.safeDrawingPadding())
                }
            }
        }
    }
}
