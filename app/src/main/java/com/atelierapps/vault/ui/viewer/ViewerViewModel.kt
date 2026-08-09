package com.atelierapps.vault.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.crypto.DekCache
import com.atelierapps.vault.data.entity.MediaWithTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs the full-screen viewer (spec §8). Loads a one-shot ordered snapshot so
 * the pager is stable, and deletes an item's row + blobs + cached DEKs.
 */
class ViewerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = VaultGraph.repository(app)
    private val storage = VaultGraph.storage(app)

    val media = MutableStateFlow<List<MediaWithTags>>(emptyList())
    val loaded = MutableStateFlow(false)

    fun load() {
        viewModelScope.launch {
            media.value = repo.allMedia()
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

    fun delete(id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.deleteMedia(id)
            withContext(Dispatchers.IO) {
                DekCache.remove(storage.thumb(id).absolutePath)
                DekCache.remove(storage.blob(id).absolutePath)
                storage.delete(id)
            }
            onDone()
        }
    }
}
