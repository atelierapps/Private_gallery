package com.atelierapps.vault.ui.export

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.atelierapps.vault.auth.BiometricAuth

/**
 * Hosts the export flow (spec §11). Exporting decrypts the **entire** vault to a
 * folder in cleartext, so it's gated behind a fresh biometric / device-credential
 * confirmation — a stronger bar than merely having the app unlocked, since anyone
 * holding an unlocked phone could otherwise dump everything.
 *
 * FragmentActivity because androidx BiometricPrompt requires it.
 */
class ExportActivity : FragmentActivity() {

    private val vm: ExportViewModel by viewModels()

    // false until the user re-confirms with biometrics; the folder picker and the
    // decrypt run are unreachable until then.
    private var authed by mutableStateOf(false)
    private var authError by mutableStateOf<String?>(null)

    private val treeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.run(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    if (!authed) {
                        ExportAuthGate(
                            error = authError,
                            onAuthenticate = ::promptAuth,
                            onCancel = { finish() },
                            modifier = Modifier.safeDrawingPadding(),
                        )
                    } else {
                        val phase by vm.phase.collectAsState()
                        val progress by vm.progress.collectAsState()
                        val result by vm.result.collectAsState()
                        ExportScreen(
                            phase = phase,
                            progress = progress,
                            result = result,
                            onPickFolder = { treeLauncher.launch(null) },
                            onClose = { finish() },
                            modifier = Modifier.safeDrawingPadding(),
                        )
                    }
                }
            }
        }

        promptAuth()
    }

    private fun promptAuth() {
        authError = null
        BiometricAuth.authenticate(
            activity = this,
            onSuccess = { authed = true },
            onError = { msg -> authError = msg.toString() },
            title = "Confirm export",
            subtitle = "Exporting decrypts your whole library to a folder",
        )
    }
}
