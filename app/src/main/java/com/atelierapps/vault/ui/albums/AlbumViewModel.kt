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
import com.atelierapps.vault.ui.home.SortOrder
import com.atelierapps.vault.ui.home.matchesQuery
import com.atelierapps.vault.ui.home.sortedFor

/** Contents of a single album (spec §7): the member grid plus multi-select. */
class AlbumViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = VaultGraph.repository(app)

    private val albumId = MutableStateFlow<String?>(null)
    val selectionMode = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val working = MutableStateFlow(false)

    // Search and sort, because a three-hundred-item album was a wall you
    // scrolled: the main grid had both and this screen had neither, so the same
    // library answered the same question differently depending on where you
    // asked. Unlike the grid's, these are per-visit — an album is somewhere you
    // arrive looking for something, not a view you live in.
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _sort = MutableStateFlow(SortOrder.NEWEST)
    val sort: StateFlow<SortOrder> = _sort

    fun setQuery(q: String) { _query.value = q }
    fun setSort(s: SortOrder) { _sort.value = s }

    val media: StateFlow<List<MediaWithTags>> =
        combine(repo.observeAll(), albumId, _query, _sort) { list, aid, q, s ->
            if (aid == null) {
                emptyList()
            } else {
                var out = list.filter { it.media.albumId == aid }
                if (q.isNotBlank()) {
                    val ql = q.trim().lowercase()
                    out = out.filter { it.matchesQuery(ql) }
                }
                out.sortedFor(s)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setAlbum(id: String) { albumId.value = id }

    // Range-select anchor — same behaviour as the main grid, so the gesture
    // means the same thing wherever you are.
    private var anchorId: String? = null

    /** Long-press: starts selection, or extends it from the anchor to here. */
    fun longPress(id: String) {
        if (selectionMode.value) selectRangeTo(id) else startSelection(id)
    }

    private fun selectRangeTo(id: String) {
        val visible = media.value.map { it.media.id }
        val from = visible.indexOf(anchorId ?: id)
        val to = visible.indexOf(id)
        if (from < 0 || to < 0) { toggleSelection(id); return }
        selectedIds.value = selectedIds.value + visible.subList(minOf(from, to), maxOf(from, to) + 1)
        anchorId = id
    }

    fun startSelection(id: String) {
        selectionMode.value = true
        selectedIds.value = setOf(id)
        anchorId = id
    }

    fun toggleSelection(id: String) {
        anchorId = id
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
        anchorId = null
    }

    /** Pin the single selected item as this album's cover. */
    fun setCoverFromSelection() {
        val id = selectedIds.value.singleOrNull() ?: return
        val album = albumId.value ?: return
        viewModelScope.launch {
            repo.setAlbumCover(album, id)
            clearSelection()
        }
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
