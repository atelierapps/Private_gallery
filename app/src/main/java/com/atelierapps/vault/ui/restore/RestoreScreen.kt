package com.atelierapps.vault.ui.restore

import android.app.Application
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.media.RestoreProgress
import com.atelierapps.vault.media.RestoreResult
import com.atelierapps.vault.media.VaultRestorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.ui.theme.VaultTheme
import androidx.compose.material3.MaterialTheme
import com.atelierapps.vault.ui.lock.FinishOnLock
import com.atelierapps.vault.media.BackupCrypto
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.atelierapps.vault.ui.theme.Danger

enum class RestorePhase { PICK, PASSPHRASE, RUNNING, DONE }

class RestoreViewModel(app: Application) : AndroidViewModel(app) {
    val phase = MutableStateFlow(RestorePhase.PICK)
    val progress = MutableStateFlow(RestoreProgress(0, 0, 0, 0))
    val result = MutableStateFlow<RestoreResult?>(null)

    /** Set when the chosen folder turned out to be an encrypted backup. */
    val wrongPassphrase = MutableStateFlow(false)
    private var pending: Uri? = null

    fun pick(treeUri: Uri) {
        if (phase.value == RestorePhase.RUNNING) return
        pending = treeUri
        // Ask before starting rather than failing partway: an encrypted folder
        // announces itself, so there is no reason to guess.
        if (VaultRestorer.isEncrypted(getApplication(), treeUri)) {
            phase.value = RestorePhase.PASSPHRASE
        } else {
            run(treeUri, null)
        }
    }

    fun run(treeUri: Uri? = pending, passphrase: CharArray?) {
        val uri = treeUri ?: return
        if (phase.value == RestorePhase.RUNNING) return
        wrongPassphrase.value = false
        phase.value = RestorePhase.RUNNING
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching {
                VaultRestorer.restoreAll(getApplication(), uri, passphrase) { progress.value = it }
            }
            val failure = r.exceptionOrNull()
            when {
                failure is BackupCrypto.WrongPassphrase -> {
                    wrongPassphrase.value = true
                    phase.value = RestorePhase.PASSPHRASE
                }
                r.isSuccess -> {
                    result.value = r.getOrNull()
                    phase.value = RestorePhase.DONE
                }
                else -> {
                    result.value = RestoreResult(0, 0, 0, hadManifest = false)
                    phase.value = RestorePhase.DONE
                }
            }
        }
    }
}

/**
 * Restore the vault from an export folder (spec §11 round-trip). Pick the folder
 * that holds your `manifest.json`; each file is re-encrypted back in with its
 * tags and source. Already-present items are skipped (dedup).
 */
class RestoreActivity : ComponentActivity() {

    private val vm: RestoreViewModel by viewModels()

    private val treeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            vm.pick(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            FinishOnLock { finish() }
            VaultTheme {
                Surface(Modifier.fillMaxSize()) {
                    val phase by vm.phase.collectAsState()
                    val progress by vm.progress.collectAsState()
                    val result by vm.result.collectAsState()
                    val wrong by vm.wrongPassphrase.collectAsState()
                    RestoreBody(
                        phase, progress, result,
                        wrongPassphrase = wrong,
                        onPick = { treeLauncher.launch(null) },
                        onPassphrase = { vm.run(passphrase = it.toCharArray()) },
                        onClose = { finish() },
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RestoreBody(
    phase: RestorePhase,
    progress: RestoreProgress,
    result: RestoreResult?,
    wrongPassphrase: Boolean,
    onPick: () -> Unit,
    onPassphrase: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Bg).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(top = 40.dp),
        ) {
            Text("Restore from backup", color = Ink, style = MaterialTheme.typography.titleLarge)
            when (phase) {
                RestorePhase.PICK -> {
                    Text(
                        "Choose the folder your backup was written to. Everything in it is " +
                            "saved back in with its tags and source, and anything already " +
                            "here is skipped.\n\nIf it was made with a passphrase you'll be " +
                            "asked for it next.",
                        color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    )
                    Button(onClick = onPick) { Text("Choose backup folder") }
                    TextButton(onClick = onClose) { Text("Cancel", color = Muted) }
                }
                RestorePhase.PASSPHRASE -> {
                    var entry by remember { mutableStateOf("") }
                    Text(
                        "This backup is encrypted. Enter the passphrase you set when you " +
                            "made it — there is no way to open it without one.",
                        color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    )
                    OutlinedTextField(
                        value = entry,
                        onValueChange = { entry = it },
                        placeholder = { Text("Passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (wrongPassphrase) {
                        Text(
                            "That passphrase doesn't open this backup.",
                            color = Danger, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = { onPassphrase(entry) }, enabled = entry.isNotEmpty()) {
                        Text("Restore")
                    }
                    TextButton(onClick = onClose) { Text("Cancel", color = Muted) }
                }
                RestorePhase.RUNNING -> {
                    Text("Restoring ${progress.done} / ${progress.total}", color = Ink, style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(
                        progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
                        color = Brass,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
                RestorePhase.DONE -> {
                    val r = result
                    val msg = when {
                        r == null -> "Done"
                        !r.hadManifest -> "No manifest.json found in that folder."
                        else -> "Restored ${r.imported} of ${r.total}" +
                            if (r.failed > 0) " · ${r.failed} skipped/failed" else ""
                    }
                    Text(msg, color = Ink, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Button(onClick = onClose) { Text("Done") }
                }
            }
        }
    }
}
