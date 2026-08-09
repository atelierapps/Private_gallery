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

    val media: StateFlow<List<MediaWithTags>> =
        combine(repo.observeAll(), _filter, _sort) { list, f, s ->
            val filtered = if (f.isEmpty) list else list.filter { f.matches(it, System.currentTimeMillis()) }
            when (s) {
                SortOrder.NEWEST -> filtered.sortedByDescending { it.media.dateTakenMillis }
                SortOrder.OLDEST -> filtered.sortedBy { it.media.dateTakenMillis }
                SortOrder.NAME -> filtered.sortedBy { it.media.originalName.lowercase() }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setType(t: com.atelierapps.vault.filter.MediaTypeFilter) {
        _filter.value = _filter.value.withType(t)
    }
    fun setSort(s: SortOrder) { _sort.value = s }

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

    /** Permanently delete the selected items from the vault (row + blobs + DEKs). */
    fun deleteSelected() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        working.value = true
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { id -> removeFromVault(id) }
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
                if (MediaExporter.toGallery(getApplication(), entity)) removeFromVault(id)
            }
            working.value = false
            clearSelection()
        }
    }

    private suspend fun removeFromVault(id: String) {
        repo.deleteMedia(id)
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
