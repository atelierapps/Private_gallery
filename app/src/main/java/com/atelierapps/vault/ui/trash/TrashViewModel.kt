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
import kotlinx.coroutines.withContext

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

    // ---- multi-select ----
    // Restoring a deletion you regret is almost never one file: you empty a
    // folder, realise, and want it all back. One tap at a time was the slowest
    // possible way to do the most likely thing.
    val selectionMode = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private var anchorId: String? = null

    fun longPress(id: String) {
        if (selectionMode.value) selectRangeTo(id) else startSelection(id)
    }

    private fun startSelection(id: String) {
        selectionMode.value = true
        selectedIds.value = setOf(id)
        anchorId = id
    }

    private fun selectRangeTo(id: String) {
        val visible = items.value.map { it.media.id }
        val from = visible.indexOf(anchorId ?: id)
        val to = visible.indexOf(id)
        if (from < 0 || to < 0) { toggleSelection(id); return }
        selectedIds.value = selectedIds.value + visible.subList(minOf(from, to), maxOf(from, to) + 1)
        anchorId = id
    }

    fun toggleSelection(id: String) {
        anchorId = id
        val next = selectedIds.value.let { if (id in it) it - id else it + id }
        selectedIds.value = next
        if (next.isEmpty()) selectionMode.value = false
    }

    fun selectAll() {
        val all = items.value.map { it.media.id }.toSet()
        if (all.isNotEmpty()) {
            selectionMode.value = true
            selectedIds.value = all
        }
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
        selectionMode.value = false
        anchorId = null
    }

    fun restore(id: String) {
        viewModelScope.launch { repo.restoreMedia(id) }
    }

    /** Put the whole selection back. */
    fun restoreSelected(onDone: (Int) -> Unit = {}) {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) return
        working.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ids.forEach { repo.restoreMedia(it) } }
            working.value = false
            clearSelection()
            onDone(ids.size)
        }
    }

    /** Permanently delete the whole selection. */
    fun purgeSelected(onDone: (Int) -> Unit = {}) {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) return
        working.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ids.forEach { purgeInternal(it) } }
            working.value = false
            clearSelection()
            onDone(ids.size)
        }
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
