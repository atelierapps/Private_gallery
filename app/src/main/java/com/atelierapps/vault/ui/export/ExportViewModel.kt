package com.atelierapps.vault.ui.export

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.media.ExportProgress
import com.atelierapps.vault.media.ExportResult
import com.atelierapps.vault.media.VaultExporter
import com.atelierapps.vault.media.ExistingBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.atelierapps.vault.session.BackupPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import com.atelierapps.vault.media.TransferWake
import com.atelierapps.vault.media.TransferFailure
import android.util.Log

enum class ExportPhase { PICK, CONFIRM_REPLACE, RUNNING, DONE }

/**
 * State and scope for a run in progress, held outside the ViewModel on purpose.
 *
 * A backup of a few hundred files to a cloud folder takes long enough that
 * nobody is going to sit and watch it, and a viewModelScope job dies the moment
 * you leave the screen — so a run that was nearly done would silently stop and
 * start again from nothing. Living here, it keeps going while you use the rest
 * of the app, and coming back to the screen re-attaches to the same run rather
 * than starting a second one.
 *
 * It does not survive the process being killed. That would take WorkManager and
 * a visible notification, which is a poor trade for an app that is trying not
 * to announce itself.
 */
private object ExportRun {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val phase = MutableStateFlow(ExportPhase.PICK)
    val progress = MutableStateFlow(ExportProgress(0, 0, 0))
    val result = MutableStateFlow<ExportResult?>(null)
    val existing = MutableStateFlow<ExistingBackup?>(null)
    var pendingTree: Uri? = null
}

private const val TAG = "ExportViewModel"

class ExportViewModel(app: Application) : AndroidViewModel(app) {

    val phase = ExportRun.phase
    val progress = ExportRun.progress
    val result = ExportRun.result

    /** Ids to export, or null for the whole library. */
    var scopeIds: Set<String>? = null

    /** Passphrase for an encrypted backup, or null to write plaintext. */
    var passphrase: CharArray? = null

    /** Set when the chosen folder already holds a backup, so we can ask first. */
    val existing = ExportRun.existing

    /**
     * Folder chosen. Runs straight away unless there is already a backup there,
     * in which case the user is asked — because a second export re-keys the
     * folder and the first backup stops opening, passphrase or no passphrase.
     */
    fun offer(treeUri: Uri) {
        if (phase.value == ExportPhase.RUNNING) return
        ExportRun.scope.launch {
            val found = runCatching { VaultExporter.existingBackup(getApplication(), treeUri) }
                .getOrNull()
            if (found == null) {
                run(treeUri)
            } else {
                ExportRun.pendingTree = treeUri
                ExportRun.existing.value = found
                phase.value = ExportPhase.CONFIRM_REPLACE
            }
        }
    }

    fun confirmReplace() {
        val uri = ExportRun.pendingTree ?: return
        ExportRun.pendingTree = null
        ExportRun.existing.value = null
        run(uri)
    }

    fun cancelReplace() {
        ExportRun.pendingTree = null
        ExportRun.existing.value = null
        // The phrase was typed for a run that isn't happening; don't keep it.
        passphrase?.fill('\u0000')
        passphrase = null
        phase.value = ExportPhase.PICK
    }

    fun run(treeUri: Uri) {
        if (phase.value == ExportPhase.RUNNING) return
        phase.value = ExportPhase.RUNNING
        result.value = null
        ExportRun.scope.launch {
            TransferWake.acquire(getApplication())
            // Caught, not left to propagate. An unhandled throw here escaped the
            // scope and left `phase` stuck on RUNNING for the life of the
            // process — after which the screen showed a frozen progress bar and
            // `run` refused to start again, with no way back short of force-stop.
            val outcome = try {
                runCatching {
                    VaultExporter.exportAll(
                        getApplication(), treeUri, scopeIds, passphrase,
                    ) { progress.value = it }
                }
            } finally {
                TransferWake.release()
            }
            val r = outcome.getOrElse { error ->
                Log.e(TAG, "export failed outright", error)
                ExportResult(
                    exported = 0,
                    failed = 1,
                    total = 0,
                    failures = listOf(
                        TransferFailure("This backup", TransferFailure.describe(error)),
                    ),
                )
            }
            // The derived key is gone with the export; don't leave the phrase
            // that makes it sitting in memory for the rest of the session.
            passphrase?.fill('\u0000')
            passphrase = null
            // Only a whole-library run with nothing failed is a backup you could
            // actually rebuild from. A scoped export is a share, and a partial
            // one leaves gaps — recording either as "backed up" would be a
            // comfortable lie the user later pays for.
            if (scopeIds == null && r.failed == 0 && r.exported > 0) {
                BackupPrefs.recordFullBackup(getApplication())
            }
            result.value = r
            phase.value = ExportPhase.DONE
        }
    }
}
