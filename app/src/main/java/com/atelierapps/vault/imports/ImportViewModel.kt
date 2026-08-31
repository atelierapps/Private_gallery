package com.atelierapps.vault.imports

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.data.entity.SourceType
import com.atelierapps.vault.filter.MediaTypeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImportTab { DEVICE, FOLDER }

/** Progress of an import run, including what dedup skipped. */
data class ImportProgress(
    val done: Int = 0,
    val total: Int = 0,
    val duplicates: Int = 0,
    val failed: Int = 0,
) {
    val imported: Int get() = (done - duplicates - failed).coerceAtLeast(0)
}

/**
 * Drives the importer (spec §4, §4.1, §15.5). Two tabs: all device media, or
 * browse by folder (MediaStore buckets — no SAF, so MIUI can't block it), plus a
 * system file picker for cloud/Downloads. A Photos/Videos type filter narrows the
 * picker.
 *
 * The encrypt work itself is handed to [ImportWorker] via the on-disk
 * [ImportQueue], so it survives leaving this screen — or the app. This view model
 * only watches that queue and, once it drains, surfaces the originals for one
 * batched `createDeleteRequest`. Delete-originals defaults OFF.
 */
class ImportViewModel(app: Application) : AndroidViewModel(app) {

    val tab = MutableStateFlow(ImportTab.DEVICE)
    val typeFilter = MutableStateFlow(MediaTypeFilter.ALL)
    val selected = MutableStateFlow<Set<Uri>>(emptySet())
    val deleteOriginals = MutableStateFlow(false) // §15.5: OFF by default
    val importing = MutableStateFlow(false)
    val progress = MutableStateFlow(ImportProgress())
    val finished = MutableStateFlow(false)
    val pendingDeviceDelete = MutableStateFlow<List<Uri>?>(null)

    /** Set when a run completes, so the screen can report what actually landed. */
    val summary = MutableStateFlow<ImportProgress?>(null)

    val folders = MutableStateFlow<List<MediaFolder>>(emptyList())
    val currentFolder = MutableStateFlow<MediaFolder?>(null)

    private val allItems = MutableStateFlow<List<DeviceMedia>>(emptyList())
    private var watchJob: Job? = null

    /** Only auto-close the importer for a run the user started on this screen. */
    private var startedHere = false

    /** Items shown in the grid = current view, narrowed by the type filter. */
    val items: StateFlow<List<DeviceMedia>> =
        combine(allItems, typeFilter) { list, type ->
            when (type) {
                MediaTypeFilter.IMAGE -> list.filter { !it.isVideo }
                MediaTypeFilter.VIDEO -> list.filter { it.isVideo }
                MediaTypeFilter.ALL -> list
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Re-attach to a run still draining from an earlier visit, and pick up any
        // originals whose delete prompt we never got to show.
        watchQueue(resumeOnly = true)
    }

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

    /**
     * Tags applied to everything in this batch. Labelling at the moment you
     * import is the only time you reliably know what the batch *is* — afterwards
     * it's mixed into the library and has to be found again first.
     */
    val batchTags = MutableStateFlow("")
    fun setBatchTags(value: String) { batchTags.value = value }

    private fun parsedTags(): List<String> =
        batchTags.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

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

    /** Queue the current selection. Returns immediately; the worker does the work. */
    fun startImport() {
        val chosen = allItems.value.filter { it.uri in selected.value }
        if (chosen.isEmpty()) return
        val tags = parsedTags()
        val entries = chosen.map {
            ImportQueue.Entry(
                uri = it.uri,
                mimeType = it.mimeType,
                displayName = it.displayName,
                dateTakenMillis = it.dateTakenMillis,
                origin = it.origin,
                bucketName = it.bucketName,
                tagNames = tags,
            )
        }
        startedHere = true
        selected.value = emptySet()
        importing.value = true
        progress.value = ImportProgress(0, entries.size)
        viewModelScope.launch(Dispatchers.IO) {
            ImportWorker.enqueue(getApplication(), entries, deleteOriginals.value)
            watchQueue()
        }
    }

    /**
     * Queue files picked through the system document picker (SAF) — cloud
     * providers, Downloads, any folder — so media can be brought in without
     * routing through the gallery. Metadata is resolved now, while the picker's
     * grant is definitely live; the bytes are read later by the worker.
     */
    fun importDocumentUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val tags = parsedTags()
        startedHere = true
        importing.value = true
        progress.value = ImportProgress(0, uris.size)
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val entries = uris.mapNotNull { uri ->
                val mime = resolver.getType(uri) ?: return@mapNotNull null
                if (!mime.startsWith("image/") && !mime.startsWith("video/")) return@mapNotNull null

                var name = uri.lastPathSegment?.substringAfterLast('/') ?: "IMG"
                var modified = System.currentTimeMillis()
                runCatching {
                    resolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val nameCol = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameCol >= 0 && !c.isNull(nameCol)) name = c.getString(nameCol)
                            val modCol = c.getColumnIndex(
                                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                            )
                            if (modCol >= 0 && !c.isNull(modCol)) modified = c.getLong(modCol)
                        }
                    }
                }
                ImportQueue.Entry(uri, mime, name, modified, SourceType.LOCAL_IMPORT, null, tags)
            }
            // Picked files are already outside the gallery; never offer to delete them.
            ImportWorker.enqueue(getApplication(), entries, deleteOriginals = false)
            watchQueue()
        }
    }

    /**
     * Poll the on-disk queue rather than WorkManager's observers: the queue is the
     * real source of truth, survives process death, and stays correct if the
     * worker is stopped and retried mid-batch.
     */
    private fun watchQueue(resumeOnly: Boolean = false) {
        if (watchJob?.isActive == true) return
        watchJob = viewModelScope.launch {
            // resumeOnly: started at construction, so bail out quietly when there
            // is nothing in flight rather than reporting a phantom finished run.
            if (resumeOnly) {
                val initial = readState()
                if (initial.remaining == 0 && initial.pendingDelete.isEmpty()) return@launch
            }
            while (true) {
                val s = readState()
                progress.value = ImportProgress(s.done, s.total, s.duplicates, s.failed)
                importing.value = s.remaining > 0
                if (s.remaining == 0) {
                    if (s.pendingDelete.isNotEmpty()) pendingDeviceDelete.value = s.pendingDelete
                    else finishRun()
                    break
                }
                delay(400)
            }
        }
    }

    private suspend fun readState(): ImportQueue.State =
        withContext(Dispatchers.IO) { ImportQueue.state(getApplication()) }

    private fun finishRun() {
        viewModelScope.launch {
            val s = readState()
            if (startedHere && s.total > 0) {
                summary.value = ImportProgress(s.done, s.total, s.duplicates, s.failed)
            }
            withContext(Dispatchers.IO) {
                ImportQueue.clearPendingDelete(getApplication())
                ImportQueue.resetIfDrained(getApplication())
            }
            importing.value = false
            // Nothing to acknowledge (e.g. resumed run finishing in the background).
            if (summary.value == null && startedHere) finished.value = true
        }
    }

    fun onDeviceDeleteFinished() {
        pendingDeviceDelete.value = null
        finishRun()
    }

    /** Summary acknowledged — close the importer. */
    fun dismissSummary() {
        summary.value = null
        if (startedHere) finished.value = true
    }
}
