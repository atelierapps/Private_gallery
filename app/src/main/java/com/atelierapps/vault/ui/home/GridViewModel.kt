package com.atelierapps.vault.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.crypto.DekCache
import com.atelierapps.vault.data.entity.MediaWithTags
import com.atelierapps.vault.data.entity.TagEntity
import com.atelierapps.vault.filter.DateBucket
import com.atelierapps.vault.filter.MediaFilter
import com.atelierapps.vault.media.MediaExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Grid ordering options. */
enum class SortOrder(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    NAME("Name A–Z"),
    LONGEST("Longest first"),
    SHORTEST("Shortest first"),
}

/** A source-filter chip: an app that media came from, with its total count (spec §7). */
data class SourceChip(
    val pkg: String,
    val label: String,
    val count: Int,
    val colorArgb: Long,
)

/**
 * Home-screen state (spec §7, §8): the filtered media list plus the source and
 * tag chips that populate the filter bar. Source/tag counts are unfiltered
 * totals; the media list reflects the active [MediaFilter].
 */
class GridViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = VaultGraph.repository(app)
    private val storage = VaultGraph.storage(app)

    // ---- multi-select ----
    val selectionMode = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val working = MutableStateFlow(false) // true during a bulk delete/move

    private val _filter = MutableStateFlow(MediaFilter())
    val filter: StateFlow<MediaFilter> = _filter

    private val _sort = MutableStateFlow(SortOrder.NEWEST)
    val sort: StateFlow<SortOrder> = _sort

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    val sourceChips: StateFlow<List<SourceChip>> =
        repo.observeSourceCounts()
            .map { rows ->
                rows.map {
                    SourceChip(
                        pkg = it.pkg,
                        label = it.label ?: it.pkg.substringAfterLast('.'),
                        count = it.count,
                        colorArgb = SourceColors.forPackage(it.pkg),
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tagChips: StateFlow<List<TagEntity>> =
        repo.observeTags()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val trashCount: StateFlow<Int> =
        repo.observeTrashCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val albums: StateFlow<List<com.atelierapps.vault.data.entity.AlbumEntity>> =
        repo.observeAlbums()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val media: StateFlow<List<MediaWithTags>> =
        combine(repo.observeAll(), _filter, _sort, _query) { list, f, s, q ->
            var out = if (f.isEmpty) list else list.filter { f.matches(it, System.currentTimeMillis()) }
            if (q.isNotBlank()) {
                val ql = q.trim().lowercase()
                out = out.filter { matchesQuery(it, ql) }
            }
            val sorted = when (s) {
                SortOrder.NEWEST -> out.sortedByDescending { it.media.dateTakenMillis }
                SortOrder.OLDEST -> out.sortedBy { it.media.dateTakenMillis }
                SortOrder.NAME -> out.sortedBy { it.media.originalName.lowercase() }
                // Images have no duration; park them after the videos either way
                // rather than letting a null masquerade as length zero.
                SortOrder.LONGEST -> out.sortedByDescending { it.media.durationMillis ?: -1L }
                SortOrder.SHORTEST -> out.sortedWith(
                    compareBy(
                        { it.media.durationMillis == null },
                        { it.media.durationMillis ?: Long.MAX_VALUE },
                    ),
                )
            }
            // Pinned items float to the top (stable — keeps sort order within groups).
            sorted.sortedByDescending { it.media.isPinned }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun matchesQuery(item: MediaWithTags, ql: String): Boolean =
        item.media.originalName.lowercase().contains(ql) ||
            item.media.sourceLabel?.lowercase()?.contains(ql) == true ||
            item.media.sourceDomain?.lowercase()?.contains(ql) == true ||
            item.tags.any { it.name.lowercase().contains(ql) }

    fun setType(t: com.atelierapps.vault.filter.MediaTypeFilter) {
        _filter.value = _filter.value.withType(t)
    }
    fun setSort(s: SortOrder) { _sort.value = s }
    fun setQuery(q: String) { _query.value = q }

    // ---- selection actions ----

    fun startSelection(id: String) {
        selectionMode.value = true
        selectedIds.value = setOf(id)
    }

    fun toggleSelection(id: String) {
        val cur = selectedIds.value
        val next = if (id in cur) cur - id else cur + id
        selectedIds.value = next
        if (next.isEmpty()) selectionMode.value = false
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
        selectionMode.value = false
    }

    /** Select every currently-visible item. */
    fun selectAll() {
        val all = media.value.map { it.media.id }.toSet()
        if (all.isNotEmpty()) {
            selectionMode.value = true
            selectedIds.value = all
        }
    }

    /** Apply tags to every selected item (retroactive/bulk tagging, spec §7 v2). */
    fun tagSelected(tagNames: List<String>) {
        val ids = selectedIds.value
        val clean = tagNames.map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty() || clean.isEmpty()) return
        working.value = true
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { repo.addTags(it, clean) }
            working.value = false
            clearSelection()
        }
    }

    /** Add the selected items to an existing album (retroactive, spec §7). */
    fun addSelectedToAlbum(albumId: String) {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        working.value = true
        viewModelScope.launch(Dispatchers.IO) {
            repo.setAlbumForItems(ids, albumId)
            working.value = false
            clearSelection()
        }
    }

    /** Create a new album and drop the current selection into it. */
    fun addSelectedToNewAlbum(name: String) {
        val ids = selectedIds.value
        val clean = name.trim()
        if (ids.isEmpty() || clean.isEmpty()) return
        working.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val albumId = repo.createAlbum(clean)
            repo.setAlbumForItems(ids, albumId)
            working.value = false
            clearSelection()
        }
    }

    /** Move the selected items to the recycle bin (soft delete; blobs kept). */
    fun deleteSelected() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        working.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            ids.forEach { id -> repo.trashMedia(id, now) }
            working.value = false
            clearSelection()
        }
    }

    /** Decrypt the selected items back into the device gallery, then remove from the vault. */
    fun moveSelectedToGallery() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        val byId = media.value.associateBy { it.media.id }
        working.value = true
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { id ->
                val entity = byId[id]?.media ?: return@forEach
                // Moving out of the vault is a real removal, not a recycle-bin
                // trip — the media now lives in the gallery, so purge the blobs.
                if (MediaExporter.toGallery(getApplication(), entity)) purgeFromVault(id)
            }
            working.value = false
            clearSelection()
        }
    }

    private suspend fun purgeFromVault(id: String) {
        repo.purgeMedia(id)
        DekCache.remove(storage.thumb(id).absolutePath)
        DekCache.remove(storage.blob(id).absolutePath)
        storage.delete(id)
    }
    fun toggleSource(pkg: String) { _filter.value = _filter.value.toggleSource(pkg) }
    fun toggleTag(name: String) { _filter.value = _filter.value.toggleTag(name) }
    fun setDate(bucket: DateBucket) { _filter.value = _filter.value.withDate(bucket) }
    fun clearAll() { _filter.value = MediaFilter() }
}

/** Deterministic, muted chip color per source package. */
object SourceColors {
    private val palette = longArrayOf(
        0xFF3B7A4E, 0xFF4A78C4, 0xFFB07F3E, 0xFF9A5AA6, 0xFF3E9B9B, 0xFFC0684E,
    )
    fun forPackage(pkg: String): Long =
        palette[(pkg.hashCode() and 0x7fffffff) % palette.size]
}
