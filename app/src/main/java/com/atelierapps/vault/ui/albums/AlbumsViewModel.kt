package com.atelierapps.vault.ui.albums

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.data.entity.AlbumEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An album plus its live member count and a cover item id (most recent member). */
data class AlbumCard(val album: AlbumEntity, val count: Int, val coverId: String?)

/** Backs the albums list (spec §7). */
class AlbumsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = VaultGraph.repository(app)

    val albums: StateFlow<List<AlbumCard>> =
        combine(repo.observeAlbums(), repo.observeAll()) { albums, media ->
            albums.map { a ->
                val members = media.filter { it.media.albumId == a.id }
                // Honour an explicitly chosen cover, but fall back if that item
                // has since left the album (moved out, trashed, deleted).
                val chosen = a.coverId?.takeIf { id -> members.any { it.media.id == id } }
                val cover = chosen ?: members.maxByOrNull { it.media.importedAtMillis }?.media?.id
                AlbumCard(a, members.size, cover)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch { repo.createAlbum(clean) }
    }

    fun rename(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch { repo.renameAlbum(id, clean) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.deleteAlbum(id) }
    }
}
