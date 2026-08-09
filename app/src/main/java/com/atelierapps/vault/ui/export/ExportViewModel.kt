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

enum class ExportPhase { PICK, RUNNING, DONE }

class ExportViewModel(app: Application) : AndroidViewModel(app) {

    val phase = MutableStateFlow(ExportPhase.PICK)
    val progress = MutableStateFlow(ExportProgress(0, 0, 0))
    val result = MutableStateFlow<ExportResult?>(null)

    fun run(treeUri: Uri) {
        if (phase.value == ExportPhase.RUNNING) return
        phase.value = ExportPhase.RUNNING
        viewModelScope.launch(Dispatchers.IO) {
            val r = VaultExporter.exportAll(getApplication(), treeUri) { progress.value = it }
            result.value = r
            phase.value = ExportPhase.DONE
        }
    }
}
