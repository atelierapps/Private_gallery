package com.atelierapps.vault.ui.trash

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.crypto.DekCache
import com.atelierapps.vault.data.entity.MediaWithTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Recycle bin (spec §8). Soft-deleted items live here until the owner restores
 * them, purges them, or the [RETENTION_MS] retention window lapses. Purging is
 * the only place trashed blobs actually leave the disk.
 */
class TrashViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = VaultGraph.repository(app)
    private val storage = VaultGraph.storage(app)

    val working = MutableStateFlow(false)

    val items: StateFlow<List<MediaWithTags>> =
        repo.observeTrash()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(id: String) {
        viewModelScope.launch { repo.restoreMedia(id) }
    }

    /** Permanently delete a single item: row + tag links + blobs + cached DEKs. */
    fun purge(id: String) {
        working.value = true
        viewModelScope.launch(Dispatchers.IO) {
            purgeInternal(id)
            working.value = false
        }
    }

    /** Empty the whole bin. */
    fun purgeAll() {
        working.value = true
        viewModelScope.launch(Dispatchers.IO) {
            repo.allTrash().forEach { purgeInternal(it.media.id) }
            working.value = false
        }
    }

    private suspend fun purgeInternal(id: String) {
        repo.purgeMedia(id)
        DekCache.remove(storage.thumb(id).absolutePath)
        DekCache.remove(storage.blob(id).absolutePath)
        storage.delete(id)
    }

    companion object {
        /** Auto-purge window: trashed items older than this are gone for good. */
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }
}
