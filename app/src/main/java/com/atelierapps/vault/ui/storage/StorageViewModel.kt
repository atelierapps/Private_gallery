package com.atelierapps.vault.ui.storage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.filter.MediaFilter
import com.atelierapps.vault.media.MediaIntegrity
import com.atelierapps.vault.media.TransferFailure
import com.atelierapps.vault.media.TransferWake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ensureActive
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
    // ---- watching: all from the view history, resume points and queue the
    // viewer already keeps. Nothing new is recorded for these.
    /** Opened in the viewer at least once (history starts at schema 9). */
    val opened: Int = 0,
    val totalViews: Int = 0,
    val inProgress: Int = 0,
    val watchLater: Int = 0,
    val bookmarked: Int = 0,
    /** Combined length of every video. */
    val videoMillis: Long = 0,
    /** Tag name to the views of the items carrying it, most first. */
    val topWatchedTags: List<Pair<String, Int>> = emptyList(),
    /** Tag name to how many items carry it, most first. */
    val topUsedTags: List<Pair<String, Int>> = emptyList(),
) {
    val items: Int get() = photos + videos
    val totalBytes: Long get() = liveBytes + thumbBytes + trashBytes + tempBytes
}

/** Progress and outcome of a library check. */
data class CheckState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val intact: Int = 0,
    /** Imported before hashes were kept: size checked, content couldn't be. */
    val sizeOnly: Int = 0,
    /** Named, never just counted. */
    val problems: List<TransferFailure> = emptyList(),
    val finished: Boolean = false,
)

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
    val check = MutableStateFlow(CheckState())

    init { refresh() }

    fun refresh() {
        loading.value = true
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) { measure() }
            stats.value = s
            loading.value = false
        }
    }

    /**
     * Decrypt every live item and compare it with the hash taken at import.
     *
     * Holds the transfer wake so the screen going off or the auto-lock timer
     * doesn't stop it partway — the auto-lock waits on the same flag a backup
     * does. Lives in viewModelScope on purpose: leaving the screen cancels it,
     * which is harmless for something read-only that can simply be run again.
     */
    fun runCheck() {
        if (check.value.running) return
        val app = getApplication<Application>()
        check.value = CheckState(running = true)
        viewModelScope.launch(Dispatchers.IO) {
            TransferWake.acquire(app, "Checking files")
            try {
                // Videos first: they are what was reported broken, so their
                // answer arrives before the photos'.
                val items = repo.allMedia().map { it.media }
                    .sortedByDescending { it.mimeType.startsWith("video/") }
                val problems = ArrayList<TransferFailure>()
                var intact = 0
                var sizeOnly = 0
                items.forEachIndexed { i, item ->
                    // The check itself never suspends, so without this a
                    // cancelled run would keep reading the whole library — and
                    // keep holding the wake that defers the auto-lock.
                    ensureActive()
                    val report = MediaIntegrity.check(app, item)
                    when (report.verdict) {
                        MediaIntegrity.Verdict.OK -> intact++
                        MediaIntegrity.Verdict.UNVERIFIABLE -> sizeOnly++
                        else -> problems.add(TransferFailure(item.originalName, report.reason))
                    }
                    check.value = CheckState(
                        running = true,
                        done = i + 1,
                        total = items.size,
                        intact = intact,
                        sizeOnly = sizeOnly,
                        problems = problems.toList(),
                    )
                }
                check.value = check.value.copy(running = false, finished = true)
            } finally {
                TransferWake.release()
            }
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

        val viewsByTag = HashMap<String, Int>()
        val itemsByTag = HashMap<String, Int>()
        live.forEach { item ->
            item.tags.forEach { t ->
                itemsByTag[t.name] = (itemsByTag[t.name] ?: 0) + 1
                if (item.media.viewCount > 0) {
                    viewsByTag[t.name] = (viewsByTag[t.name] ?: 0) + item.media.viewCount
                }
            }
        }
        val top: (Map<String, Int>) -> List<Pair<String, Int>> = { m ->
            m.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
                .take(TOP_TAGS).map { it.key to it.value }
        }

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
            opened = live.count { it.media.viewCount > 0 },
            totalViews = live.sumOf { it.media.viewCount },
            inProgress = live.count {
                it.media.mimeType.startsWith("video/") &&
                    MediaFilter.isInProgress(
                        it.media.resumePositionMillis, it.media.durationMillis,
                        it.media.lastViewedAtMillis, System.currentTimeMillis(),
                    )
            },
            watchLater = live.count { it.media.watchLaterAtMillis != null },
            bookmarked = live.count { it.media.bookmarkList().isNotEmpty() },
            videoMillis = live.sumOf { if (it.media.mimeType.startsWith("video/")) it.media.durationMillis ?: 0L else 0L },
            topWatchedTags = top(viewsByTag),
            topUsedTags = top(itemsByTag),
        )
    }

    private companion object {
        /** Long enough to be useful, short enough to read at a glance. */
        const val TOP_TAGS = 5
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
