package com.atelierapps.vault.imports

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.data.entity.SourceType
import com.atelierapps.vault.media.MediaSaver
import com.atelierapps.vault.media.SaveRequest
import com.atelierapps.vault.media.SourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ImportTab { DEVICE, FOLDER }

/** Progress of an in-flight import. */
data class ImportProgress(val done: Int, val total: Int)

/**
 * Drives the importer (spec §4, §4.1, §15.5). Encrypts selected items through
 * the shared [MediaSaver], tracks progress, and — only after every write is
 * verified — surfaces originals for deletion. Delete-originals defaults OFF.
 */
class ImportViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = VaultGraph.storage(app)

    val tab = MutableStateFlow(ImportTab.DEVICE)
    val items = MutableStateFlow<List<DeviceMedia>>(emptyList())
    val selected = MutableStateFlow<Set<Uri>>(emptySet())
    val deleteOriginals = MutableStateFlow(false) // §15.5: OFF by default
    val importing = MutableStateFlow(false)
    val progress = MutableStateFlow(ImportProgress(0, 0))
    val finished = MutableStateFlow(false)

    /** Non-null when device originals are verified-imported and awaiting the system delete dialog. */
    val pendingDeviceDelete = MutableStateFlow<List<Uri>?>(null)

    fun selectDeviceTab() {
        tab.value = ImportTab.DEVICE
        loadDevices()
    }

    private fun loadDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            items.value = DeviceMediaSource.queryDevice(getApplication(), limit = 1000, offset = 0)
        }
    }

    fun onFolderPicked(treeUri: Uri) {
        tab.value = ImportTab.FOLDER
        selected.value = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            items.value = DeviceMediaSource.listFolder(getApplication(), treeUri)
        }
    }

    fun toggle(uri: Uri) {
        val cur = selected.value
        selected.value = if (uri in cur) cur - uri else cur + uri
    }

    fun setDeleteOriginals(value: Boolean) { deleteOriginals.value = value }

    fun startImport() {
        if (importing.value) return
        val chosen = items.value.filter { it.uri in selected.value }
        if (chosen.isEmpty()) return

        importing.value = true
        progress.value = ImportProgress(0, chosen.size)

        viewModelScope.launch(Dispatchers.IO) {
            val saver = MediaSaver(getApplication())
            val succeeded = ArrayList<Uri>()
            var done = 0
            for (item in chosen) {
                runCatching { importOne(saver, item) }
                    .onSuccess { if (it) succeeded += item.uri }
                done++
                progress.value = ImportProgress(done, chosen.size)
            }

            when {
                !deleteOriginals.value || succeeded.isEmpty() -> finished.value = true
                chosen.first().origin == SourceType.FOLDER_IMPORT -> {
                    deleteFolderOriginals(succeeded)
                    finished.value = true
                }
                else -> pendingDeviceDelete.value = succeeded // Activity launches createDeleteRequest
            }
        }
    }

    private suspend fun importOne(saver: MediaSaver, item: DeviceMedia): Boolean {
        val temp = spool(item.uri)
        val label = if (item.origin == SourceType.FOLDER_IMPORT) "Folder import" else null
        val request = SaveRequest(
            tempPath = temp.absolutePath,
            mimeType = item.mimeType,
            originalName = item.displayName,
            dateTakenMillis = item.dateTakenMillis,
            tagNames = emptyList(),
            source = SourceInfo(item.origin, null, label, null),
        )
        return saver.save(request).isSuccess
    }

    private suspend fun spool(uri: Uri): File = withContext(Dispatchers.IO) {
        val temp = storage.newTempFile()
        getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
            temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        temp
    }

    private fun deleteFolderOriginals(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        uris.forEach { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
    }

    fun onDeviceDeleteFinished() {
        pendingDeviceDelete.value = null
        finished.value = true
    }
}
