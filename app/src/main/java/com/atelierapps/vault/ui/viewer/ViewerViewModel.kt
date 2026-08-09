package com.atelierapps.vault.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.data.entity.MediaWithTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the full-screen viewer (spec §8). Loads a one-shot ordered snapshot so
 * the pager is stable, and moves an item to the recycle bin on delete.
 */
class ViewerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = VaultGraph.repository(app)

    val media = MutableStateFlow<List<MediaWithTags>>(emptyList())
    val loaded = MutableStateFlow(false)

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
}
