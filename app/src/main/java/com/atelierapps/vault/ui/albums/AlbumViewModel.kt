package com.atelierapps.vault.ui.albums

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.data.entity.MediaWithTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Contents of a single album (spec §7): the member grid plus multi-select. */
class AlbumViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = VaultGraph.repository(app)

    private val albumId = MutableStateFlow<String?>(null)
    val selectionMode = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val working = MutableStateFlow(false)

    val media: StateFlow<List<MediaWithTags>> =
        combine(repo.observeAll(), albumId) { list, aid ->
            if (aid == null) {
                emptyList()
            } else {
                list.filter { it.media.albumId == aid }
                    .sortedByDescending { it.media.dateTakenMillis }
                    .sortedByDescending { it.media.isPinned }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setAlbum(id: String) { albumId.value = id }

    fun startSelection(id: String) {
        selectionMode.value = true
        selectedIds.value = setOf(id)
    }

    fun toggleSelection(id: String) {
        val next = selectedIds.value.let { if (id in it) it - id else it + id }
        selectedIds.value = next
        if (next.isEmpty()) selectionMode.value = false
    }

    fun selectAll() {
        val all = media.value.map { it.media.id }.toSet()
        if (all.isNotEmpty()) {
            selectionMode.value = true
            selectedIds.value = all
        }
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
        selectionMode.value = false
    }

    /** Remove selected items from this album — they stay in the library. */
    fun removeSelected() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        working.value = true
        viewModelScope.launch(Dispatchers.IO) {
            repo.setAlbumForItems(ids, null)
            working.value = false
            clearSelection()
        }
    }

    /** Move selected items to the recycle bin (soft delete). */
    fun deleteSelected() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        working.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            ids.forEach { repo.trashMedia(it, now) }
            working.value = false
            clearSelection()
        }
    }
}
