package com.atelierapps.vault.imports

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.data.entity.SourceType
import com.atelierapps.vault.filter.MediaTypeFilter
import com.atelierapps.vault.media.MediaSaver
import com.atelierapps.vault.media.SaveRequest
import com.atelierapps.vault.media.SourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ImportTab { DEVICE, FOLDER }

/** Progress of an in-flight import. */
data class ImportProgress(val done: Int, val total: Int)

/**
 * Drives the importer (spec §4, §4.1, §15.5). Two tabs: all device media, or
 * browse by folder (MediaStore buckets — no SAF, so MIUI can't block it). A
 * Photos/Videos type filter narrows the picker. Encrypts the selection through
 * the shared [MediaSaver], and — only after every write is verified — surfaces
 * originals for one batched `createDeleteRequest`. Delete-originals defaults OFF.
 */
class ImportViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = VaultGraph.storage(app)

    val tab = MutableStateFlow(ImportTab.DEVICE)
    val typeFilter = MutableStateFlow(MediaTypeFilter.ALL)
    val selected = MutableStateFlow<Set<Uri>>(emptySet())
    val deleteOriginals = MutableStateFlow(false) // §15.5: OFF by default
    val importing = MutableStateFlow(false)
    val progress = MutableStateFlow(ImportProgress(0, 0))
    val finished = MutableStateFlow(false)
    val pendingDeviceDelete = MutableStateFlow<List<Uri>?>(null)

    val folders = MutableStateFlow<List<MediaFolder>>(emptyList())
    val currentFolder = MutableStateFlow<MediaFolder?>(null)

    private val allItems = MutableStateFlow<List<DeviceMedia>>(emptyList())

    /** Items shown in the grid = current view, narrowed by the type filter. */
    val items: StateFlow<List<DeviceMedia>> =
        combine(allItems, typeFilter) { list, type ->
            when (type) {
                MediaTypeFilter.IMAGE -> list.filter { !it.isVideo }
                MediaTypeFilter.VIDEO -> list.filter { it.isVideo }
                MediaTypeFilter.ALL -> list
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectDeviceTab() {
        tab.value = ImportTab.DEVICE
        currentFolder.value = null
        loadDevice()
    }

    fun selectFolderTab() {
        tab.value = ImportTab.FOLDER
        currentFolder.value = null
        allItems.value = emptyList()
        loadFolders()
    }

    fun openFolder(folder: MediaFolder) {
        currentFolder.value = folder
        viewModelScope.launch(Dispatchers.IO) {
            allItems.value = DeviceMediaSource.queryDevice(
                getApplication(), limit = 5000, offset = 0,
                bucketId = folder.bucketId, origin = SourceType.FOLDER_IMPORT,
            )
        }
    }

    fun backToFolders() {
        currentFolder.value = null
        allItems.value = emptyList()
    }

    fun setType(t: MediaTypeFilter) { typeFilter.value = t }

    fun toggle(uri: Uri) {
        val cur = selected.value
        selected.value = if (uri in cur) cur - uri else cur + uri
    }

    fun setDeleteOriginals(value: Boolean) { deleteOriginals.value = value }

    private fun loadDevice() {
        viewModelScope.launch(Dispatchers.IO) {
            allItems.value = DeviceMediaSource.queryDevice(getApplication(), limit = 2000, offset = 0)
        }
    }

    private fun loadFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            folders.value = DeviceMediaSource.queryFolders(getApplication())
        }
    }

    fun startImport() {
        if (importing.value) return
        val chosen = allItems.value.filter { it.uri in selected.value }
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
            Log.i(TAG, "imported ${succeeded.size}/${chosen.size}, deleteOriginals=${deleteOriginals.value}")
            // All originals are MediaStore items → one batched createDeleteRequest.
            if (deleteOriginals.value && succeeded.isNotEmpty()) {
                Log.i(TAG, "requesting delete of ${succeeded.size} originals")
                pendingDeviceDelete.value = succeeded
            } else {
                finished.value = true
            }
        }
    }

    private suspend fun importOne(saver: MediaSaver, item: DeviceMedia): Boolean {
        val temp = spool(item.uri)
        // The item's own folder is its provenance — carried for both the "all
        // media" tab and folder browsing, so imports are never source-less.
        return saver.save(
            SaveRequest(
                tempPath = temp.absolutePath,
                mimeType = item.mimeType,
                originalName = item.displayName,
                dateTakenMillis = item.dateTakenMillis,
                tagNames = emptyList(),
                source = SourceInfo(item.origin, null, item.bucketName, null),
            ),
        ).isSuccess
    }

    /**
     * Import files picked through the system document picker (SAF) — reaches
     * cloud providers (Drive, OneDrive), Downloads, and any folder, so media can
     * be brought in directly without routing through the gallery. Unlike Restore,
     * this needs no manifest.json; each file is spooled and encrypted as-is.
     */
    fun importDocumentUris(uris: List<Uri>) {
        if (importing.value || uris.isEmpty()) return
        importing.value = true
        progress.value = ImportProgress(0, uris.size)
        viewModelScope.launch(Dispatchers.IO) {
            val saver = MediaSaver(getApplication())
            var done = 0
            for (uri in uris) {
                runCatching { importDocument(saver, uri) }
                    .onFailure { Log.w(TAG, "skip $uri", it) }
                done++
                progress.value = ImportProgress(done, uris.size)
            }
            finished.value = true
        }
    }

    private suspend fun importDocument(saver: MediaSaver, uri: Uri): Boolean {
        val resolver = getApplication<Application>().contentResolver
        val mime = resolver.getType(uri) ?: return false
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) return false

        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "IMG"
        var modified = System.currentTimeMillis()
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameCol = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameCol >= 0 && !c.isNull(nameCol)) name = c.getString(nameCol)
                    val modCol = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (modCol >= 0 && !c.isNull(modCol)) modified = c.getLong(modCol)
                }
            }
        }

        val temp = spool(uri)
        return saver.save(
            SaveRequest(
                tempPath = temp.absolutePath,
                mimeType = mime,
                originalName = name,
                dateTakenMillis = modified,
                tagNames = emptyList(),
                source = SourceInfo(SourceType.LOCAL_IMPORT, null, null, null),
            ),
        ).isSuccess
    }

    private suspend fun spool(uri: Uri): File = withContext(Dispatchers.IO) {
        val temp = storage.newTempFile()
        getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
            temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        temp
    }

    fun onDeviceDeleteFinished() {
        pendingDeviceDelete.value = null
        finished.value = true
    }

    private companion object {
        const val TAG = "VaultImport"
    }
}
