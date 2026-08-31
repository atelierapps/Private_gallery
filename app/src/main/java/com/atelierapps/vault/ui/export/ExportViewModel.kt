package com.atelierapps.vault.ui.export

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.media.ExportProgress
import com.atelierapps.vault.media.ExportResult
import com.atelierapps.vault.media.VaultExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.atelierapps.vault.session.BackupPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import com.atelierapps.vault.media.TransferWake

enum class ExportPhase { PICK, RUNNING, DONE }

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
}

class ExportViewModel(app: Application) : AndroidViewModel(app) {

    val phase = ExportRun.phase
    val progress = ExportRun.progress
    val result = ExportRun.result

    /** Ids to export, or null for the whole library. */
    var scopeIds: Set<String>? = null

    /** Passphrase for an encrypted backup, or null to write plaintext. */
    var passphrase: CharArray? = null

    fun run(treeUri: Uri) {
        if (phase.value == ExportPhase.RUNNING) return
        phase.value = ExportPhase.RUNNING
        result.value = null
        ExportRun.scope.launch {
            TransferWake.acquire(getApplication())
            val r = try {
                VaultExporter.exportAll(
                    getApplication(), treeUri, scopeIds, passphrase,
                ) { progress.value = it }
            } finally {
                TransferWake.release()
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
