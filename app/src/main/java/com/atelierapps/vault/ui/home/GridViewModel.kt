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
import com.atelierapps.vault.session.DisplayPrefs
import kotlinx.coroutines.withContext
import com.atelierapps.vault.media.NameTemplate

/** Grid ordering options. */
enum class SortOrder(val label: String) {
    // First, and the default, because it is the one that matches how media
    // actually arrives here. See the sort block below for why.
    RECENT("Recently added"),
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

    // Sort sticks across launches, like every other display preference. Picking
    // "Longest first" once and having it silently revert to Newest next launch
    // was the odd one out.
    private val _sort = MutableStateFlow(
        DisplayPrefs.sort(app)?.let { name -> SortOrder.entries.firstOrNull { it.name == name } }
            ?: SortOrder.RECENT,
    )
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

    /**
     * How many copies could be removed without losing anything — one per group
     * beyond the first. Shown in the menu so the finder announces itself when
     * there is something to find, rather than sitting there unexplained.
     */
    val duplicateCount: StateFlow<Int> =
        repo.observeAll()
            .map { list ->
                list.filter { it.media.contentHash != null }
                    .groupingBy { it.media.contentHash }
                    .eachCount()
                    .values
                    .sumOf { (it - 1).coerceAtLeast(0) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

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
            out.sortedFor(s)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun matchesQuery(item: MediaWithTags, ql: String): Boolean = item.matchesQuery(ql)

    fun setType(t: com.atelierapps.vault.filter.MediaTypeFilter) {
        _filter.value = _filter.value.withType(t)
    }
    fun setSort(s: SortOrder) {
        _sort.value = s
        DisplayPrefs.setSort(getApplication(), s.name)
    }

    private var lastShuffle: List<String> = emptyList()

    /**
     * A fresh random order over what's currently visible. Re-rolls if the shuffle
     * came out identical to the previous one, so every press really is a new
     * order rather than occasionally repeating on small sets.
     */
    fun shuffledIds(): List<String> {
        val ids = media.value.map { it.media.id }
        if (ids.size < 2) return ids
        var next = ids.shuffled()
        var attempts = 0
        while (next == lastShuffle && attempts < 5) {
            next = ids.shuffled()
            attempts++
        }
        lastShuffle = next
        return next
    }
    fun setQuery(q: String) { _query.value = q }

    // ---- selection actions ----

    // Where a range starts. Set by the long-press that opened selection and by
    // every tap after it, so "long-press here" always means "from the last thing
    // I touched to here".
    private var anchorId: String? = null

    /**
     * Long-press: opens selection on the first item, then selects everything
     * between the anchor and this one. Picking out forty consecutive clips was
     * forty taps before this.
     */
    fun longPress(id: String) {
        if (selectionMode.value) selectRangeTo(id) else startSelection(id)
    }

    private fun selectRangeTo(id: String) {
        val visible = media.value.map { it.media.id }
        val from = visible.indexOf(anchorId ?: id)
        val to = visible.indexOf(id)
        if (from < 0 || to < 0) { toggleSelection(id); return }
        val range = visible.subList(minOf(from, to), maxOf(from, to) + 1)
        selectedIds.value = selectedIds.value + range
        anchorId = id
    }

    fun startSelection(id: String) {
        selectionMode.value = true
        selectedIds.value = setOf(id)
        anchorId = id
    }

    fun toggleSelection(id: String) {
        val cur = selectedIds.value
        anchorId = id
        val next = if (id in cur) cur - id else cur + id
        selectedIds.value = next
        if (next.isEmpty()) selectionMode.value = false
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
        selectionMode.value = false
        anchorId = null
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
    fun tagSelected(tagNames: List<String>, onDone: (Int) -> Unit = {}) {
        val ids = selectedIds.value
        val clean = tagNames.map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty() || clean.isEmpty()) return
        working.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ids.forEach { repo.addTags(it, clean) } }
            working.value = false
            clearSelection()
            onDone(ids.size)
        }
    }

    /** Add the selected items to an existing album (retroactive, spec §7). */
    fun addSelectedToAlbum(albumId: String, onDone: (Int) -> Unit = {}) {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        working.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.setAlbumForItems(ids, albumId) }
            working.value = false
            clearSelection()
            onDone(ids.size)
        }
    }

    /** Create a new album and drop the current selection into it. */
    fun addSelectedToNewAlbum(name: String, onDone: (Int) -> Unit = {}) {
        val ids = selectedIds.value
        val clean = name.trim()
        if (ids.isEmpty() || clean.isEmpty()) return
        working.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val albumId = repo.createAlbum(clean)
                repo.setAlbumForItems(ids, albumId)
            }
            working.value = false
            clearSelection()
            onDone(ids.size)
        }
    }

    /**
     * Name, tag and file a whole selection in one pass.
     *
     * Deliberately one operation rather than three trips through the selection
     * bar: a batch that has just arrived needs all three doing, and doing them
     * separately means finding the same forty items three times.
     *
     * Numbering follows the order on screen, so what the preview showed is what
     * lands — the visible order is the one the user was looking at when they
     * chose the template.
     */
    fun organiseSelected(
        template: String,
        dateFormat: String,
        tagNames: List<String>,
        sourceLabel: String?,
        onDone: (Int) -> Unit = {},
    ) {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        val ordered = media.value.filter { it.media.id in ids }
        val tags = tagNames.map { it.trim() }.filter { it.isNotEmpty() }
        val label = sourceLabel?.trim()?.takeIf { it.isNotEmpty() }
        working.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ordered.forEachIndexed { index, item ->
                    if (template.isNotBlank()) {
                        repo.renameMedia(
                            item.media.id,
                            NameTemplate.expand(template, item, index, ordered.size, dateFormat),
                        )
                    }
                    if (tags.isNotEmpty()) repo.addTags(item.media.id, tags)
                    if (label != null) repo.setSourceLabel(item.media.id, label)
                }
            }
            working.value = false
            clearSelection()
            onDone(ordered.size)
        }
    }

    /** Move the selected items to the recycle bin (soft delete; blobs kept). */
    fun deleteSelected(onDone: (List<String>) -> Unit = {}) {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) return
        working.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                ids.forEach { id -> repo.trashMedia(id, now) }
            }
            working.value = false
            clearSelection()
            onDone(ids)
        }
    }

    /** Undo a soft delete — the ids come straight back from [deleteSelected]. */
    fun restoreTrashed(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { ids.forEach { repo.restoreMedia(it) } }
    }

    /** Decrypt the selected items back into the device gallery, then remove from the vault. */
    fun moveSelectedToGallery(onDone: (moved: Int, failed: Int) -> Unit = { _, _ -> }) {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        val byId = media.value.associateBy { it.media.id }
        working.value = true
        viewModelScope.launch {
            var moved = 0
            withContext(Dispatchers.IO) {
                ids.forEach { id ->
                    val entity = byId[id]?.media ?: return@forEach
                    // Moving out of the vault is a real removal, not a recycle-bin
                    // trip — the media now lives in the gallery, so purge the blobs.
                    if (MediaExporter.toGallery(getApplication(), entity)) {
                        purgeFromVault(id)
                        moved++
                    }
                }
            }
            working.value = false
            clearSelection()
            onDone(moved, ids.size - moved)
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

/**
 * The grid's ordering, shared rather than reimplemented.
 *
 * Albums used to be hardcoded to newest-first with no search, so the same
 * library answered the same question two different ways depending on which
 * screen asked. Both now call this.
 */
fun List<MediaWithTags>.sortedFor(order: SortOrder): List<MediaWithTags> {
    val sorted = when (order) {
        // Two different questions that look like one. `dateTaken` is when the
        // photo was made — for something downloaded today that can be years
        // ago, so a batch of forty imports scatters through the whole grid and
        // the things you just added are the hardest to find. `importedAt` is
        // when it landed here.
        SortOrder.RECENT -> sortedByDescending { it.media.importedAtMillis }
        SortOrder.NEWEST -> sortedByDescending { it.media.dateTakenMillis }
        SortOrder.OLDEST -> sortedBy { it.media.dateTakenMillis }
        SortOrder.NAME -> sortedBy { it.media.originalName.lowercase() }
        // Images have no duration; park them after the videos either way rather
        // than letting a null masquerade as length zero.
        SortOrder.LONGEST -> sortedByDescending { it.media.durationMillis ?: -1L }
        SortOrder.SHORTEST -> sortedWith(
            compareBy(
                { it.media.durationMillis == null },
                { it.media.durationMillis ?: Long.MAX_VALUE },
            ),
        )
    }
    // Pinned items float to the top (stable — keeps sort order within groups).
    return sorted.sortedByDescending { it.media.isPinned }
}

/** Name, source or tag contains [ql], which must already be lowercased. */
fun MediaWithTags.matchesQuery(ql: String): Boolean =
    media.originalName.lowercase().contains(ql) ||
        media.sourceLabel?.lowercase()?.contains(ql) == true ||
        media.sourceDomain?.lowercase()?.contains(ql) == true ||
        tags.any { it.name.lowercase().contains(ql) }

/** Deterministic, muted chip color per source package. */
object SourceColors {
    private val palette = longArrayOf(
        0xFF3B7A4E, 0xFF4A78C4, 0xFFB07F3E, 0xFF9A5AA6, 0xFF3E9B9B, 0xFFC0684E,
    )
    fun forPackage(pkg: String): Long =
        palette[(pkg.hashCode() and 0x7fffffff) % palette.size]
}
