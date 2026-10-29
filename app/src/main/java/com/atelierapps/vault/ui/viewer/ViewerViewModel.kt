package com.atelierapps.vault.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.data.entity.AlbumEntity
import com.atelierapps.vault.data.entity.MediaWithTags
import com.atelierapps.vault.data.entity.TagEntity
import com.atelierapps.vault.media.MediaSharer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs the full-screen viewer (spec §8). Loads a one-shot ordered snapshot so
 * the pager is stable, and moves an item to the recycle bin on delete.
 */
class ViewerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = VaultGraph.repository(app)

    val media = MutableStateFlow<List<MediaWithTags>>(emptyList())
    val loaded = MutableStateFlow(false)

    val albums: StateFlow<List<AlbumEntity>> =
        repo.observeAlbums()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tags: StateFlow<List<TagEntity>> =
        repo.observeTags()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun load() {
        viewModelScope.launch {
            val all = repo.allMedia()
            val ids = ViewerSession.orderedIds
            media.value = if (ids != null) {
                val byId = all.associateBy { it.media.id }
                ids.mapNotNull { byId[it] } // preserve the grid's exact order/context
            } else {
                all
            }
            loaded.value = true
        }
    }

    /** Whether the order on screen is a shuffle, for the button's brass state. */
    val shuffled = MutableStateFlow(false)

    // The order before shuffling, by id: ids rather than the items themselves,
    // so a rename or a tag made while shuffled isn't undone by putting the old
    // objects back.
    private var unshuffledIds: List<String>? = null

    /**
     * Shuffle on or off, around the item at [id], which never moves.
     *
     * Moving it would mean scrolling the pager after the new list lands, and
     * for that moment the old item at that page shows: a flash, and the clip
     * you were watching restarts. So both directions keep it at its own index.
     *
     * On: everything else is shuffled. Off: the original order comes back as
     * a rotation that puts this item where it already is, so what plays next
     * is what came next originally, and repeat-all wraps round to the rest.
     */
    fun toggleShuffle(id: String) {
        val list = media.value
        val k = list.indexOfFirst { it.media.id == id }
        if (k < 0) return
        if (!shuffled.value) {
            if (list.size < 2) return
            unshuffledIds = list.map { it.media.id }
            val others = (list.subList(0, k) + list.subList(k + 1, list.size)).shuffled().toMutableList()
            others.add(k, list[k])
            media.value = others
            shuffled.value = true
        } else {
            val order = unshuffledIds
            unshuffledIds = null
            shuffled.value = false
            if (order == null) return
            val byId = list.associateBy { it.media.id }
            val known = order.toHashSet()
            // Items deleted meanwhile drop out; nothing is ever added mid-run,
            // but anything unknown is kept rather than lost.
            val original = order.mapNotNull { byId[it] } + list.filter { it.media.id !in known }
            val j = original.indexOfFirst { it.media.id == id }
            val n = original.size
            if (j < 0 || n != list.size) return
            media.value = List(n) { i -> original[((j - k + i) % n + n) % n] }
        }
    }

    /** Rename an item in place, keeping the pager snapshot in sync. */
    fun rename(id: String, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            repo.renameMedia(id, clean)
            val list = media.value
            val idx = list.indexOfFirst { it.media.id == id }
            if (idx < 0) return@launch
            media.value = list.toMutableList().also {
                it[idx] = it[idx].copy(media = it[idx].media.copy(originalName = clean))
            }
        }
    }

    /**
     * Written on player release only, so scrubbing doesn't hammer the database.
     * Deliberately does not refresh the in-memory list: the snapshot backing the
     * pager is meant to be stable, and rewriting it mid-playback for a field
     * nothing on screen reads would be churn for its own sake.
     */
    fun setResumePosition(id: String, millis: Long?) {
        viewModelScope.launch { repo.setResumePosition(id, millis) }
    }

    fun setVideoRotation(id: String, degrees: Int?) {
        viewModelScope.launch { repo.setVideoRotation(id, degrees) }
    }

    /**
     * Set an item's tags from the viewer — including new ones typed on the spot.
     * Keeps the pager's snapshot in sync so the change shows without reopening.
     */
    fun setTags(id: String, names: List<String>) {
        viewModelScope.launch {
            repo.setTagsForMedia(id, names)
            val refreshed = repo.allMedia().firstOrNull { it.media.id == id } ?: return@launch
            val list = media.value
            val idx = list.indexOfFirst { it.media.id == id }
            if (idx < 0) return@launch
            media.value = list.toMutableList().also { it[idx] = refreshed }
        }
    }

    fun addToAlbum(id: String, albumId: String) {
        viewModelScope.launch { repo.setAlbumForItems(listOf(id), albumId) }
    }

    fun addToNewAlbum(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val albumId = repo.createAlbum(clean)
            repo.setAlbumForItems(listOf(id), albumId)
        }
    }

    fun togglePin(id: String) {
        viewModelScope.launch {
            val list = media.value
            val idx = list.indexOfFirst { it.media.id == id }
            if (idx < 0) return@launch
            val pinned = !list[idx].media.isPinned
            repo.setPinned(id, pinned)
            media.value = list.toMutableList().also {
                it[idx] = it[idx].copy(media = it[idx].media.copy(isPinned = pinned))
            }
        }
    }

    /** Move to the recycle bin (soft delete). Recoverable until it's purged. */
    fun delete(id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.trashMedia(id)
            onDone()
        }
    }

    /**
     * Decrypt one item to the share spool (off the UI thread) and hand back a
     * grantable content URI + mime for the activity to launch a share chooser.
     */
    fun share(id: String, onReady: (Uri, String) -> Unit, onError: () -> Unit) {
        val item = media.value.firstOrNull { it.media.id == id }?.media ?: return onError()
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching { MediaSharer.decryptForShare(getApplication(), item) }.getOrNull()
            }
            if (uri != null) onReady(uri, item.mimeType) else onError()
        }
    }
}
