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

enum class ExportPhase { PICK, RUNNING, DONE }

class ExportViewModel(app: Application) : AndroidViewModel(app) {

    val phase = MutableStateFlow(ExportPhase.PICK)
    val progress = MutableStateFlow(ExportProgress(0, 0, 0))
    val result = MutableStateFlow<ExportResult?>(null)

    /** Ids to export, or null for the whole library. */
    var scopeIds: Set<String>? = null

    fun run(treeUri: Uri) {
        if (phase.value == ExportPhase.RUNNING) return
        phase.value = ExportPhase.RUNNING
        viewModelScope.launch(Dispatchers.IO) {
            val r = VaultExporter.exportAll(getApplication(), treeUri, scopeIds) { progress.value = it }
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
