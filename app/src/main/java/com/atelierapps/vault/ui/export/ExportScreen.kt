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
import com.atelierapps.vault.media.ExistingBackup
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import androidx.compose.material3.MaterialTheme
import com.atelierapps.vault.ui.theme.Danger
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.atelierapps.vault.ui.theme.FailureList

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

/** Shortest passphrase worth calling one; below this the KDF is doing the work alone. */
private const val MIN_PASSPHRASE = 8

/** Export UI (spec §11): pick a destination, watch progress, see the verified count. */
@Composable
fun ExportScreen(
    scopedCount: Int?,
    phase: ExportPhase,
    progress: ExportProgress,
    result: ExportResult?,
    existing: ExistingBackup?,
    onConfirmReplace: () -> Unit,
    onCancelReplace: () -> Unit,
    onPickFolder: (passphrase: String?) -> Unit,
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
                    var protect by remember { mutableStateOf(true) }
                    var phrase by remember { mutableStateOf("") }
                    var confirm by remember { mutableStateOf("") }
                    val mismatch = protect && confirm.isNotEmpty() && phrase != confirm
                    val ready = !protect || (phrase.length >= MIN_PASSPHRASE && phrase == confirm)

                    Text(
                        "Everything is written out to a folder you choose, with a manifest " +
                            "that restores tags and sources later.\n\nA local folder or SD " +
                            "card takes minutes; a cloud folder like Drive uploads every " +
                            "file and can take hours. Leave this screen on — the phone can " +
                            "stop a backup that is running in the background, and it will " +
                            "not tell you it did.\n\nUse an empty folder. A folder that " +
                            "already holds a backup can only hold one, and the new run " +
                            "replaces it.",
                        color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Protect with a passphrase", color = Ink, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (protect) {
                                    "Files and the manifest are encrypted, and named so the " +
                                        "folder gives nothing away."
                                } else {
                                    "Written in the clear — anyone who finds the folder can " +
                                        "read all of it."
                                },
                                color = if (protect) Muted else Danger,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Switch(checked = protect, onCheckedChange = { protect = it })
                    }

                    if (protect) {
                        OutlinedTextField(
                            value = phrase,
                            onValueChange = { phrase = it },
                            placeholder = { Text("Passphrase") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = confirm,
                            onValueChange = { confirm = it },
                            placeholder = { Text("Type it again") },
                            singleLine = true,
                            isError = mismatch,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // Twice, because there is genuinely no way back from a
                        // typo here — not a reset, not a support line, nothing.
                        Text(
                            when {
                                mismatch -> "Those don't match."
                                phrase.isNotEmpty() && phrase.length < MIN_PASSPHRASE ->
                                    "At least $MIN_PASSPHRASE characters."
                                else ->
                                    "Write it down somewhere. If you lose it this backup is " +
                                        "lost with it — the key is made from the passphrase " +
                                        "and nothing else, so there is nothing to recover from."
                            },
                            color = if (mismatch) Danger else Muted,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }

                    Button(
                        onClick = { onPickFolder(if (protect) phrase else null) },
                        enabled = ready,
                    ) { Text("Choose folder") }
                    TextButton(onClick = onClose) { Text("Cancel", color = Muted) }
                }
                ExportPhase.CONFIRM_REPLACE -> {
                    Text(
                        "There's already a backup here",
                        color = Ink, style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        buildString {
                            append(
                                if (existing?.encrypted == true) {
                                    "That folder holds an encrypted backup"
                                } else {
                                    "That folder holds a backup"
                                },
                            )
                            val n = existing?.items ?: 0
                            if (n > 0) append(" of $n file(s)")
                            append(
                                ".\n\nA folder can only hold one. Every export makes a new " +
                                    "key, so the moment this run starts the old backup stops " +
                                    "opening — with the same passphrase or any other. There " +
                                    "is no way to undo that and no way to read it afterwards." +
                                    "\n\nIf the old one still matters, cancel and pick an " +
                                    "empty folder instead.",
                            )
                        },
                        color = Muted, style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onConfirmReplace) { Text("Replace it") }
                    TextButton(onClick = onCancelReplace) { Text("Cancel", color = Muted) }
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
                    // Named, not just counted: a backup missing three files you
                    // can't identify isn't a backup you can rely on.
                    FailureList(r?.failures.orEmpty())
                    Text(
                        "That folder is independent of this app now — it survives an " +
                            "uninstall, and copying it off the phone is what makes it a " +
                            "real backup.\n\nTo bring it back on a fresh install: menu → " +
                            "Restore from backup, then pick this folder. If you set a " +
                            "passphrase you will need it then, and only then.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onClose) { Text("Done") }
                }
            }
        }
    }
}
