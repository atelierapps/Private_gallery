package com.atelierapps.vault.ui.export

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.atelierapps.vault.media.ExportProgress
import com.atelierapps.vault.media.ExportResult
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import androidx.compose.material3.MaterialTheme
import com.atelierapps.vault.ui.theme.Danger

/** Biometric gate shown before the export flow (spec §11). */
@Composable
fun ExportAuthGate(
    error: String?,
    onAuthenticate: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Bg).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Confirm it's you", color = Ink, style = MaterialTheme.typography.titleLarge)
            Text(
                "Exporting decrypts media to a folder in the clear. " +
                    "Confirm with your fingerprint or device PIN to continue.",
                color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            )
            if (error != null) {
                Text(error, color = Danger, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            Button(onClick = onAuthenticate) { Text(if (error == null) "Confirm" else "Try again") }
            TextButton(onClick = onCancel) { Text("Cancel", color = Muted) }
        }
    }
}

/** Export UI (spec §11): pick a destination, watch progress, see the verified count. */
@Composable
fun ExportScreen(
    scopedCount: Int?,
    phase: ExportPhase,
    progress: ExportProgress,
    result: ExportResult?,
    onPickFolder: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Bg).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(top = 40.dp),
        ) {
            Text(
                if (scopedCount == null) "Export library" else "Export $scopedCount item(s)",
                color = Ink, style = MaterialTheme.typography.titleLarge,
            )
            when (phase) {
                ExportPhase.PICK -> {
                    Text(
                        "Choose a folder to back up to. Everything is decrypted to its " +
                            "original files, plus a manifest.json that can restore tags and " +
                            "sources on re-import.\n\nThis backup is unencrypted — keep it somewhere safe.",
                        color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    )
                    Button(onClick = onPickFolder) { Text("Choose folder") }
                    TextButton(onClick = onClose) { Text("Cancel", color = Muted) }
                }
                ExportPhase.RUNNING -> {
                    Text("Exporting ${progress.done} / ${progress.total}", color = Ink, style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(
                        progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
                        color = Brass,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    if (progress.failed > 0) Text("${progress.failed} failed", color = Danger, style = MaterialTheme.typography.bodySmall)
                }
                ExportPhase.DONE -> {
                    val r = result
                    Text(
                        if (r == null) "Done" else "Exported ${r.exported} of ${r.total}" +
                            if (r.failed > 0) " · ${r.failed} failed" else "",
                        color = Ink, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    )
                    Text("A manifest.json was written alongside your files.", color = Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    Button(onClick = onClose) { Text("Done") }
                }
            }
        }
    }
}
