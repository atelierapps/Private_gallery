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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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

enum class RestorePhase { PICK, RUNNING, DONE }

class RestoreViewModel(app: Application) : AndroidViewModel(app) {
    val phase = MutableStateFlow(RestorePhase.PICK)
    val progress = MutableStateFlow(RestoreProgress(0, 0, 0, 0))
    val result = MutableStateFlow<RestoreResult?>(null)

    fun run(treeUri: Uri) {
        if (phase.value == RestorePhase.RUNNING) return
        phase.value = RestorePhase.RUNNING
        viewModelScope.launch(Dispatchers.IO) {
            val r = VaultRestorer.restoreAll(getApplication(), treeUri) { progress.value = it }
            result.value = r
            phase.value = RestorePhase.DONE
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
            vm.run(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val phase by vm.phase.collectAsState()
                    val progress by vm.progress.collectAsState()
                    val result by vm.result.collectAsState()
                    RestoreBody(
                        phase, progress, result,
                        onPick = { treeLauncher.launch(null) },
                        onClose = { finish() },
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }
}

private val Bg = Color(0xFF0E1113)
private val Ink = Color(0xFFE9EEF0)
private val Muted = Color(0xFF8A969E)
private val Brass = Color(0xFFD8B463)

@Composable
private fun RestoreBody(
    phase: RestorePhase,
    progress: RestoreProgress,
    result: RestoreResult?,
    onPick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Bg).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(top = 40.dp),
        ) {
            Text("Restore from backup", color = Ink, fontSize = 22.sp)
            when (phase) {
                RestorePhase.PICK -> {
                    Text(
                        "Choose the folder that contains your manifest.json. Every file it " +
                            "lists is re-encrypted back into the vault with its tags and source. " +
                            "Items already in the vault are skipped.",
                        color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                    )
                    Button(onClick = onPick) { Text("Choose backup folder") }
                    TextButton(onClick = onClose) { Text("Cancel", color = Muted) }
                }
                RestorePhase.RUNNING -> {
                    Text("Restoring ${progress.done} / ${progress.total}", color = Ink, fontSize = 15.sp)
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
                    Text(msg, color = Ink, fontSize = 16.sp, textAlign = TextAlign.Center)
                    Button(onClick = onClose) { Text("Done") }
                }
            }
        }
    }
}
