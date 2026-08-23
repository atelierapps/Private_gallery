package com.atelierapps.vault.ui.storage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.VaultGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the vault is holding, and what it costs on disk. */
data class StorageStats(
    val photos: Int = 0,
    val videos: Int = 0,
    val liveBytes: Long = 0,
    val thumbBytes: Long = 0,
    val trashCount: Int = 0,
    val trashBytes: Long = 0,
    val albums: Int = 0,
    val tags: Int = 0,
    val tempBytes: Long = 0,
) {
    val items: Int get() = photos + videos
    val totalBytes: Long get() = liveBytes + thumbBytes + trashBytes + tempBytes
}

/**
 * Backs the storage screen. Everything is measured from the actual encrypted
 * files on disk rather than the recorded plaintext sizes, so the numbers reflect
 * what the vault really occupies.
 */
class StorageViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = VaultGraph.repository(app)
    private val storage = VaultGraph.storage(app)

    val stats = MutableStateFlow(StorageStats())
    val loading = MutableStateFlow(true)

    init { refresh() }

    fun refresh() {
        loading.value = true
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) { measure() }
            stats.value = s
            loading.value = false
        }
    }

    private suspend fun measure(): StorageStats {
        val live = repo.allMedia()
        val trashed = repo.allTrash()

        var photos = 0
        var videos = 0
        var liveBytes = 0L
        live.forEach {
            if (it.media.mimeType.startsWith("video/")) videos++ else photos++
            liveBytes += storage.blob(it.media.id).length()
        }
        val trashBytes = trashed.sumOf { storage.blob(it.media.id).length() }

        return StorageStats(
            photos = photos,
            videos = videos,
            liveBytes = liveBytes,
            thumbBytes = dirSize(storage.thumbsDir),
            trashCount = trashed.size,
            trashBytes = trashBytes,
            albums = repo.albumCount(),
            tags = repo.tagCount(),
            tempBytes = dirSize(storage.tmpDir) +
                dirSize(File(getApplication<Application>().cacheDir, "share")),
        )
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
