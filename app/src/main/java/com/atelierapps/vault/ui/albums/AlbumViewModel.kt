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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.atelierapps.vault.ui.home.SortOrder
import com.atelierapps.vault.ui.home.searchedAndSorted

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
                list.filter { it.media.albumId == aid }.searchedAndSorted(q, s)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setAlbum(id: String) { albumId.value = id }

    // ---- following tags ----

    /** The tags this album follows, or null for one filled by hand. */
    val rule: StateFlow<AlbumTagRule?> =
        combine(repo.observeAlbums(), repo.observeTags(), albumId) { albums, tags, id ->
            AlbumTagRule.decode(albums.firstOrNull { it.id == id }?.tagRule, tags)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val skips = albumId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repo.observeAlbumSkips(id)
    }

    /**
     * Items that carry this album's tags but aren't in it yet: the ones to
     * offer. Not ones already in another album (an item has one album, and
     * taking it would quietly empty that one), and not ones turned down or
     * removed before.
     */
    val pending: StateFlow<List<MediaWithTags>> =
        combine(repo.observeAll(), rule, skips, albumId) { all, r, skipped, id ->
            if (r == null || id == null) {
                emptyList()
            } else {
                val skipSet = skipped.toHashSet()
                val free = matchTags(all, r.names, r.requireAll, id).free.toHashSet()
                all.filter { it.media.id in free && it.media.id !in skipSet }
                    .sortedByDescending { it.media.importedAtMillis }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Add the [chosen] ones; the rest of [offered] are not offered again.
     * Unticking is the answer "not this one", not "ask me later".
     */
    fun reviewPending(offered: List<String>, chosen: Set<String>) {
        val album = albumId.value ?: return
        viewModelScope.launch {
            val add = offered.filter { it in chosen }
            if (add.isNotEmpty()) repo.setAlbumForItems(add, album)
            repo.skipForAlbum(album, offered.filter { it !in chosen })
        }
    }

    /** Stop offering new matches; the album keeps what it has. */
    fun stopFollowing() {
        val album = albumId.value ?: return
        viewModelScope.launch { repo.setAlbumTagRule(album, null) }
    }

    // Range-select anchor — same behaviour as the main grid, so the gesture
    // means the same thing wherever you are.
    private var anchorId: String? = null

    /** Long-press: starts selection, or extends it from the anchor to here. */
    fun longPress(id: String) {
        beforeLongPress = Triple(selectionMode.value, selectedIds.value, anchorId)
        if (selectionMode.value) selectRangeTo(id) else startSelection(id)
    }

    // As on the main grid: the long press selects before anyone can know a
    // preview flick is coming, so the flick needs to put the selection back.
    private var beforeLongPress: Triple<Boolean, Set<String>, String?>? = null

    /** Undo the last long press's selection: it turned out to be a preview. */
    fun undoLongPress() {
        val (mode, ids, anchor) = beforeLongPress ?: return
        selectionMode.value = mode
        selectedIds.value = ids
        anchorId = anchor
        beforeLongPress = null
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

    /**
     * Remove selected items from this album — they stay in the library. In an
     * album that follows tags they also go on its skip list, or they'd be
     * offered straight back the next time it opened.
     */
    fun removeSelected() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        val album = albumId.value
        working.value = true
        viewModelScope.launch(Dispatchers.IO) {
            repo.setAlbumForItems(ids, null)
            if (album != null && rule.value != null) repo.skipForAlbum(album, ids)
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
